package org.bolusai.next

import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.dexcomg7.ObservationSummaryRead

/** Synthetic Diagnóstico log for instrumentation that visits Diagnóstico without testing the log itself (ADR 0021). */
internal val emptyDexcomLog = ObservationSummaryRead.Loaded(ObservationSummary(0, 0, 0, emptyMap(), 0, 0, 0, 0, null, null))
