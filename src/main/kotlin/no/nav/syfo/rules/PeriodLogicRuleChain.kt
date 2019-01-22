package no.nav.syfo.rules

import no.nav.helse.sm2013.CV
import no.nav.helse.sm2013.HelseOpplysningerArbeidsuforhet
import no.nav.syfo.Description
import no.nav.syfo.ICD10
import no.nav.syfo.Rule
import no.nav.syfo.model.Status
import no.nav.syfo.ICPC2
import no.nav.syfo.RuleData
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class RuleMetadata(
    val signatureDate: LocalDateTime,
    val receivedDate: LocalDateTime
)

enum class PeriodLogicRuleChain(override val ruleId: Int?, override val status: Status, override val predicate: (RuleData<RuleMetadata>) -> Boolean) : Rule<RuleData<RuleMetadata>> {
    // TODO: gendate newer than signature date, check if emottak does this?
    @Description("Behandlet dato (felt 12.1) er etter dato for mottak av sykmeldingen.")
    SIGNATURE_DATE_AFTER_RECEIVED_DATE(1110, Status.INVALID, { (healthInformation, ruleMetadata) ->
        if (healthInformation.kontaktMedPasient?.behandletDato != null) {
            healthInformation.kontaktMedPasient.behandletDato > ruleMetadata.signatureDate
        } else {
            false
        }
    }),

    @Description("Hvis ingen perioder er oppgitt skal sykmeldingen avvises.")
    NO_PERIOD_PROVIDED(1200, Status.INVALID, { (healthInformation, _) ->
        healthInformation.aktivitet?.periode.isNullOrEmpty()
    }),

    @Description("Hvis tildato for en periode ligger før fradato avvises meldingen og hvilken periode det gjelder oppgis.")
    TO_DATE_BEFORE_FROM_DATE(1201, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode.any {
                it.periodeFOMDato.isAfter(it.periodeTOMDato)
            }
        } else {
            false
        }
    }),

    @Description("Hvis en eller flere perioder er overlappende avvises meldingen og hvilken periode det gjelder oppgis.")
    OVERLAPPING_PERIODS(1202, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode.any { periodA ->
                healthInformation.aktivitet.periode
                        .filter { periodB ->
                            periodB != periodA
                        }
                        .any { periodB ->
                            periodA.periodeFOMDato in periodB.range() || periodA.periodeTOMDato in periodB.range()
                        }
            }
        } else {
            false
        }
    }),

    // TODO
    @Description("Hvis det finnes opphold mellom perioder i sykmeldingen avvises meldingen.")
    GAP_BETWEEN_PERIODS(1203, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
        val ranges = healthInformation.aktivitet.periode
                .sortedBy { it.periodeFOMDato }
                .map { it.periodeFOMDato to it.periodeTOMDato }

            var gapBetweenPeriods = false
            for (i in 1..(ranges.size - 1)) {
                gapBetweenPeriods = workdaysBetween(ranges[i - 1].second, ranges[i].first) > 0
                }
            gapBetweenPeriods
        } else {
            false
        }
    }),

    @Description("Hvis fom dato i varighet er lik start dato på sykdomtilfelle og første konsultasjon er mer enn 8 dager fra start dato men ikke over et år")
    BACKDATED_MORE_THEN_8_DAYS_AND_UNDER_1_YEAR_BACKDATED(1204, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty() && healthInformation.kontaktMedPasient?.kontaktDato != null) {
                    healthInformation.aktivitet.periode.any {
                        it.periodeFOMDato == healthInformation.syketilfelleStartDato &&
                                healthInformation.kontaktMedPasient.kontaktDato > it.periodeFOMDato.plusDays(7) &&
                                healthInformation.kontaktMedPasient.kontaktDato <= it.periodeFOMDato.plusYears(1).minusDays(1)
                    }
        } else {
            false
        }
    }),

    @Description("Sykmeldinges fom-dato er mer enn 3 år tilbake i tid.")
    BACKDATED_MORE_THEN_3_YEARS(1206, Status.INVALID, { (healthInformation, ruleMetadata) ->
        if (healthInformation.kontaktMedPasient?.behandletDato != null) {
            ruleMetadata.signatureDate.minusYears(3).isAfter(healthInformation.kontaktMedPasient.behandletDato)
        } else {
            false
        }
    }),

    @Description("Sykmeldingens fom-dato er inntil 3 år tilbake i tid og årsak for tilbakedatering er angitt.")
    BACKDATED_WITH_REASON(1207, Status.MANUAL_PROCESSING, { (healthInformation, ruleMetadata) ->
        if (healthInformation.kontaktMedPasient?.behandletDato != null) {
            ruleMetadata.signatureDate.minusYears(3).isBefore(healthInformation.kontaktMedPasient.behandletDato) && !healthInformation.kontaktMedPasient.begrunnIkkeKontakt.isNullOrEmpty()
        } else {
            false
        }
    }),
    @Description("Hvis sykmeldingen er fremdatert mer enn 30 dager etter konsultasjonsdato/signaturdato avvises meldingen.")
    PRE_DATED(1209, Status.INVALID, { (healthInformation, ruleMetadata) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode.sortedFOMDate().first().atStartOfDay() > ruleMetadata.signatureDate.plusDays(30)
        } else {
            false
        }
    }),

    @Description("Hvis sykmeldingens sluttdato er mer enn ett år frem i tid, avvises meldingen.")
    END_DATE(1211, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty() && healthInformation.kontaktMedPasient?.behandletDato != null) {
            healthInformation.aktivitet.periode.sortedTOMDate().last().atStartOfDay() > healthInformation.kontaktMedPasient.behandletDato.plusYears(1)
        } else {
            false
        }
    }),

    @Description("Hvis behandletdato er etter dato for mottak av meldingen avvises meldingen")
    RECEIVED_DATE_BEFORE_PROCESSED_DATE(1123, Status.INVALID, { (healthInformation, ruleMetadata) ->
        if (healthInformation.kontaktMedPasient?.behandletDato != null) {
            healthInformation.kontaktMedPasient.behandletDato > ruleMetadata.receivedDate.plusHours(2)
        } else {
            false
        }
    }),

    // TODO: Is this even supposed to be here?
    // NOPE pål would delete this one
    @Description("Hvis avventende sykmelding er funnet og det finnes mer enn en periode")
    PENDING_SICK_LEAVE_COMBINED(1240, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            val numberOfPendingPeriods = healthInformation.aktivitet.periode.count { it.avventendeSykmelding != null }
            numberOfPendingPeriods != 0 && healthInformation.aktivitet.periode.size > 1
        } else {
            false
        }
    }),

    @Description("Hvis innspill til arbeidsgiver om tilrettelegging i pkt 4.1.3 ikke er utfylt ved avventende sykmelding avvises meldingen")
    MISSING_INSPILL_TIL_ARBEIDSGIVER(1241, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode
                    .filter { it.avventendeSykmelding != null }
                    .any { (it.avventendeSykmelding.innspillTilArbeidsgiver == null) }
        } else {
            false
        }
    }),

    @Description("Hvis avventende sykmelding benyttes utover i arbeidsgiverperioden på 16 kalenderdager, avvises meldingen.")
    PENDING_PERIOD_OUTSIDE_OF_EMPLOYER_PAID(1242, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode
                    .filter { it.avventendeSykmelding != null }
                    .any { (it.periodeFOMDato..it.periodeTOMDato).daysBetween() > 16 }
        } else {
            false
        }
    }),

    @Description("Hvis antall dager oppgitt for behandlingsdager periode er for høyt i forhold til periodens lengde avvises meldingen. Mer enn en dag per uke er for høyt. 1 dag per påbegynt uke.")
    TOO_MANY_TREATMENT_DAYS(1250, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode.any {
                it.behandlingsdager != null && it.behandlingsdager.antallBehandlingsdagerUke > it.range().startedWeeksBetween()
            }
        } else {
            false
        }
    }),

    @Description("Hvis sykmeldingsgrad er mindre enn 20% for gradert sykmelding, avvises meldingen")
    PARTIAL_SICK_LEAVE_PERCENTAGE_TO_LOW(1251, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode.any {
                it.gradertSykmelding != null && it.gradertSykmelding.sykmeldingsgrad < 20
            }
        } else {
            false
        }
    }),

    @Description("Hvis sykmeldingsgrad er høyere enn 99% for delvis sykmelding avvises meldingen")
    PARTIAL_SICK_LEAVE_TOO_HIGH_PERCENTAGE(1252, Status.INVALID, { (healthInformation, _) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode.filter { it.gradertSykmelding != null }.any { it.gradertSykmelding.sykmeldingsgrad > 99 }
        } else {
            false
        }
    }),

    // TODO: Check persisted sykmelding if there is a gap of less then 16 days from the previous one
    @Description("Fom-dato i ny sykmelding som er en forlengelse kan maks være tilbakedatert 1 mnd fra signaturdato. Skal telles.")
    BACKDATING_SYKMELDING_EXTENSION(null, Status.INVALID, { (healthInformation, ruleMetadata) ->
        if (!healthInformation.aktivitet?.periode.isNullOrEmpty()) {
            healthInformation.aktivitet.periode.sortedFOMDate().first().minusMonths(1).atStartOfDay() > ruleMetadata.signatureDate
        } else {
            false
        }
    }),
}

fun List<HelseOpplysningerArbeidsuforhet.Aktivitet.Periode>.sortedFOMDate(): List<LocalDate> =
        map { it.periodeFOMDato }.sorted()
fun List<HelseOpplysningerArbeidsuforhet.Aktivitet.Periode>.sortedTOMDate(): List<LocalDate> =
        map { it.periodeTOMDato }.sorted()

fun workdaysBetween(a: LocalDate, b: LocalDate): Int = (1..(ChronoUnit.DAYS.between(a, b) - 1))
        .map { a.plusDays(it) }
        .filter { it.dayOfWeek !in arrayOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
        .count()

fun ClosedRange<LocalDate>.daysBetween(): Long = ChronoUnit.DAYS.between(start, endInclusive)
fun ClosedRange<LocalDate>.startedWeeksBetween(): Long = ChronoUnit.WEEKS.between(start, endInclusive) + 1
fun HelseOpplysningerArbeidsuforhet.Aktivitet.Periode.range(): ClosedRange<LocalDate> =
        periodeFOMDato.rangeTo(periodeTOMDato)

fun CV.isICPC2(): Boolean = s == ICPC2.A01.oid

fun CV.toICPC2(): List<ICPC2>? = if (isICPC2()) { listOfNotNull(ICPC2.values().find { it.codeValue == v }) } else { ICD10.values().find { it.codeValue == v }?.icpc2 }

val diagnoseCodesSimplified = listOf(
        ICPC2.D70, ICPC2.D73, ICPC2.F70, ICPC2.F73, ICPC2.H71, ICPC2.R72, ICPC2.R74, ICPC2.R75, ICPC2.R76, ICPC2.R77, ICPC2.R78, ICPC2.R80, ICPC2.R81, ICPC2.U71
)