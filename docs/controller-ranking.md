# Controller ranking: flags and criteria

`ControllerRankingModel` (see its own class doc-comment for the full tiebreak stack) exposes six
booleans on each `RankedController`. This doc is the detailed reference for what sets each one --
kept out of the class doc-comment itself since the full table is long. See issue #8 for the
original tier-chain/route-match/distance design and issue #9 for the VATGlasses sector/boundary
geometry that upgrades several of these rows.

## Design principle: `IsHighlighted` vs `IsNext` vs `IsLikelyNext`

Since the issue #18 redesign, this is three different kinds of signal, not one flag at two
thresholds:

- **`IsHighlighted`** is relevance/visibility -- "this station is worth seeing" (flight-plan match,
  proximity, or polygon containment/convergence), independent of whether it's the one to actually
  contact next.
- **`IsNext`** is confident and actionable -- exactly one qualifying candidate, unambiguous (already
  contained, an unambiguous inner-radius match, or a single converging/entering candidate whose
  route-relevance is also confirmed).
- **`IsLikelyNext`** is the same underlying signal as `IsNext` but confidence-capped -- either
  because multiple candidates are genuinely tied (see each bucket's own tie rule), or because
  route-relevance itself is unconfirmed (not on the flight plan) even when the geometry is
  unambiguous.

Containment/proximity (already there, or clearly close) generally produces more confidence than
prediction (not there yet, projected to converge) -- see bucket 8's satisfied-vs-converging split
for where that distinction still matters directly.

## Flag/criteria table

`Bucket` refers to the numbered ranking-order buckets defined in "Sort order" below -- included
here so you don't have to cross-reference to see where a given flag lands in the list. Buckets 6,
7, and 8 (below) are each split into lettered sub-rows since their criteria don't fit one row --
within a bucket, sub-rows are checked in order to build the `IsHighlighted` set, then the last
sub-row resolves `IsNext`/`IsLikelyNext` from within it. **Buckets 6/7/8 supersede the old
`IsApproaching`/`IsLikelyNextCandidate`/ATIS-only-`IsHighlighted` design entirely** (issue #18
redesign, 2026-07-28) -- `IsNext`/`IsLikelyNext` are new flags replacing `IsLikelyNextCandidate`,
and `IsHighlighted` is no longer ATIS-specific. Bucket 6 is the on-ground case (AGL<50ft) for
DEL/GND/TWR/APP/CTR; bucket 7 is the airborne case for TWR/APP; bucket 8 is the airborne case for
CTR (covers level and non-level flight both, via 8a's satisfied-or-converging vertical check), plus
an independent ETA readout (8c) layered on top of 8a/8b rather than a separate bucket -- there's no
"bucket 9" case here after all, resolving what looked like a naming collision with bucket 9's
existing meaning (the final catch-all rank position in "Sort order" below). Every bucket's exact
final ranking position relative to the
others (and whether `IsNext`/`IsLikelyNext` occupy one rank or two) is not decided yet -- see "Sort
order" below, still stale/pending for buckets 6-8.

| Bucket | Flag | Tier(s) | Trigger criteria | Notes |
|---|---|---|---|---|
| 1 | `IsCurrent` | any | Tuned COM1 or COM2 frequency matches | Always ranked first. COM1 and COM2 can each independently match a different real online station -- both get `IsCurrent` simultaneously, not just whichever a single lookup found first. When both match, COM1's station is always ordered ahead of COM2's within this bucket. |
| 2 | `IsStandbyTuned` | any | Callsign's frequency matches COM1 or COM2 *standby* | Ranked immediately below current, regardless of tier. |
| 3 | `IsContactMe` | any | Callsign present in `ContactMeModel.ActiveCallsigns` | Tuning that frequency (COM1 or COM2 active) clears the contact-me request. |
| 4 | `IsSelcalActive` | any | Callsign present in `SelcalActiveModel.ActiveCallsigns` | Cleared by the client's `dismissSelcal` command -- does not auto-clear on tune-match. |
| 5 | (no dedicated flag) | any | Callsign matches `SetPinnedController`'s pinned callsign | Manual toggle (`pinController`/`clearPinnedController`), pilot-controlled only -- never auto-cleared by becoming current/standby. If the pinned station becomes current or standby-tuned, it moves to that higher-priority bucket instead (never both places at once), but the pin badge still shows alongside TUNED/STBY, since Android tracks it independently of bucket position. |
| 6a | `IsHighlighted` | any (incl. ATIS/`Other`) | Callsign ICAO-prefix-matches the flight plan's origin, destination, or alternate | Checked first, before any range/polygon rule below -- unconditional the moment the station is online (visible in `IBroker`), regardless of distance or geometry. Subsumes the old ATIS-only highlight rule entirely -- ATIS gets highlighted through this same row now, no dedicated rule needed. ATIS has no equivalent to 6b-6d (no radius/polygon fallback -- an unrelated nearby airport's ATIS isn't worth highlighting) and never participates in 6e (`Other` tier is always skipped by the chain-walk, same as today). |
| 6b | `IsHighlighted` | DEL/GND/TWR | Not on flight plan: VATGlasses polygon containment where available, else within 5nm | 5nm comfortably covers even the world's largest airport by land area (King Fahd International, ~780km<sup>2</sup>) -- its actual runway/taxiway complex is only a few km across, the rest is empty buffer land. |
| 6c | `IsHighlighted` | APP | Not on flight plan: VATGlasses polygon containment where available, else within 20nm flat (vertical ignored entirely) | |
| 6d | `IsHighlighted` | CTR | Not on flight plan: horizontal-only polygon containment, no radius fallback at all -- vertical band ignored entirely | Real-world VATSIM top-down coverage means an online enroute Center covers straight to the ground for anything inside its lateral boundary, regardless of the nominal FL its data lists as a floor (that FL shows up in the controller's own info string, not as a hard boundary on responsibility). No polygon data for a given CTR -- neither `IsHighlighted` nor `IsNext`/`IsLikelyNext`, full stop; a distance guess for CTR is exactly the kind of unreliable heuristic already rejected pre-issue-#9, worse still without any polygon at all. |
| 6e | `IsNext` / `IsLikelyNext` | DEL/GND/TWR/APP/CTR | Chain-walk from whatever's tuned on COM1, over only the set of stations that qualified for `IsHighlighted` above (6a-6d) -- first qualifying tier above current tier wins | A tier already passed (e.g. DEL once tuned to GND) drops out of `IsNext`/`IsLikelyNext` but stays `IsHighlighted` (still useful for e.g. a re-clearance). Within the winning tier: exactly one qualifying candidate online -- confident `IsNext`. More than one genuinely tied candidate simultaneously online (e.g. identical control-zone polygon shared by per-runway TWR pairs; or differently-owned overlapping CTR sectors) -- all of them get `IsLikelyNext` instead of a single arbitrary pick. Resolves to one via elimination (only one of the tied set is actually online) -- confident `IsNext`, no longer ambiguous. Among multiple simultaneously-online `IsLikelyNext` CTR candidates, lowest `max` altitude is the tentative display-order preference (unverified against a real differently-owned-overlap example yet -- see project memory). |
| 7a | `IsHighlighted` | TWR | Airborne (mutually exclusive with bucket 6 -- 6 applies on the ground, AGL<50ft; 7 applies once airborne) AND AGL<10000ft: within 20nm if on flight plan (origin/destination/alternate), else within 10nm | |
| 7b | `IsHighlighted` | APP/DEP | Airborne AND below the ceiling (the sector's own published upper FL + 5000ft where VATGlasses defines one, else a flat FL290 fallback -- no lower bound at all): distance to the sector polygon (where VATGlasses coverage exists) or straight-line distance to the station otherwise, <=30nm -- same radius regardless of flight-plan status | Unlike 6c, no polygon-vs-radius branching by coverage changes the threshold -- 30nm applies either way, polygon distance is just a more precise measurement of it where available. The ceiling is deliberately not a single flat FL: a real gap hit on departure (Tower handed off to APP early, clear of conflict, before being within APP's nominal lower band) is what ruled out a lower bound entirely, and per-sector data is preferred over the flat fallback where VATGlasses actually publishes an upper FL for that controller's own sector. |
| 7c | `IsNext` / `IsLikelyNext` | TWR | Confident `IsNext` within the inner radius (10nm if on flight plan, 5nm if not) | Same tie rule as 6e: more than one TWR simultaneously within its inner radius -- all get `IsLikelyNext` instead of an arbitrary pick. |
| 7c | `IsNext` / `IsLikelyNext` | APP/DEP | Only when actually converging/"entering" the sector (route/heading-projected prediction, not merely within the 7b highlight radius) | Confident `IsNext` only if on the flight plan AND exactly one entering candidate. `IsLikelyNext` otherwise -- either because not on the flight plan at all (route-relevance is uncertain even if geometrically unambiguous, e.g. an unplanned diversion into LOWW's airspace with LOWW not an actual alternate -- capped at `IsLikelyNext` even with only one entering candidate), or because multiple simultaneously-entering candidates exist regardless of flight-plan status (e.g. LOWW_APP and LZIB_APP both plausible depending on approach direction). |
| 8a | `IsHighlighted` | CTR | Airborne: route/heading-projected lateral convergence (150nm route-projected preferred, 100nm heading-ray-cast fallback when no route loaded, same as before) AND vertical -- either *satisfied* (ownship's altitude is already within the sector's band, regardless of level/climbing/descending -- no margin, no trend needed) or *converging* (sustained climb/descent trend, vertical speed >=500fpm for >=5s, same `VerticalTrendThresholdFpm`/`VerticalTrendSustainWindow` as before, bringing ownship within 5000ft of the band edge it's headed toward -- widened from the old 2000ft, since a fast-climbing/descending bizjet can close that gap quickly) | No VATGlasses (or VatSpy) geometry for a given CTR -- stays unmarked entirely, same principle as 6d (known gap, accepted for now). CTR is a more relaxed phase of flight than TWR/APP -- a real handoff is normally either an explicit pass from the previous controller or a contact-me, so this prediction is a nice-to-know, not the critical signal the tighter TWR/APP buckets are. The margin only matters for the converging case -- level flight already inside the band needs no margin at all, it's just already there. |
| 8b | `IsNext` / `IsLikelyNext` | CTR | Among the 8a-qualifying (converging-into) set, take the single closest by lateral distance as the band anchor -- every candidate within `anchor x 1.10` of it (not pairwise-chained, always relative to that one anchor) ties with it | Exactly one candidate within the band (nothing else that close) -- confident `IsNext`. More than one within the band -- all of them get `IsLikelyNext` instead. At the 150nm range ceiling this is roughly a 15nm-wide band, which is still a reasonable "basically tied" margin at that distance. |
| 8c | ETA readout (ownship-level, not per-controller) | -- | Level flight, any altitude -- OR climbing/descending above FL150 | Independent of 8a/8b -- not a gate on `IsHighlighted`/`IsNext`/`IsLikelyNext`, just whether an ETA number gets shown alongside whatever those already resolved. Level flight has no altitude floor at all (even a prop plane cruising at 5000ft gets one, since level flight implies stable speed regardless of altitude); climbing/descending needs FL150 specifically because speed/profile changes too much below it to trust an estimate. FL150 is a single flat threshold, not aircraft-type-aware -- deliberately not worth the SimConnect engine/category-detection work this would need to do properly for a soft UX nicety, not a correctness-critical flag. An unpressurized prop plane may never even reach FL150, but its climb is short regardless (even at a modest 1000fpm, well under 10 minutes to a typical prop cruise altitude), so the "no ETA yet" gap in practice is brief. Long-haul descents from high cruise altitudes are the main beneficiary -- a 30+ minute descent from FL410 stays well above FL150 for most of its length. |
| 9 | (no flags) | any | Everything that didn't qualify for any bucket above | The original issue #8 base case, untouched by the #18 redesign. Ordered by chain tier (DEL->GND->TWR->APP->CTR relative to current tier), then by distance within tier. |

## Sort order

The Android client renders the list in exactly the order the plugin sends it -- no client-side
re-sorting, ever. `ControllerRankingModel.Recompute()` builds that order as ascending `Bucket`
number (1 through 9, per the flag/criteria table above -- that table is the source of truth for
each bucket's criteria, not restated here).

**Within a bucket**, order is:

1. `IsNext` (confident) first.
2. `IsLikelyNext` next, ordered by distance only -- a tie group is guaranteed same-tier by
   construction (ties only ever form among same-type candidates, e.g. two TWRs or two CTRs), so
   tier ordering would be a no-op here.
3. Everything else that's merely `IsHighlighted` (no `IsNext`/`IsLikelyNext`), ordered by chain
   tier then distance -- unlike the `IsLikelyNext` group, this remainder can span multiple tiers
   at once, so tier-first keeps them predictably grouped (all DEL together, then GND, then TWR...)
   rather than jumbled by raw distance across tiers.

This only meaningfully applies to buckets 6/7/8, which are the only ones mixing `IsNext`/
`IsLikelyNext`/`IsHighlighted` together -- buckets 1-5 and 9 each have simpler, single-purpose
ordering already described in their own table rows/notes.

A controller-issued diversion (the VATSIM-filed destination changing mid-session) also affects
bucket 8's route-projected prediction, not just sort order directly -- see "Diversion invalidates
the filed route" below.

## VATGlasses match parameters: distance / altitude / heading

Two distinct checks, matching the design principle above -- heading is deliberately irrelevant to
one and central to the other.

### Containment (bucket 6b/6c's `IsHighlighted`, preferred over the radius fallback where available) -- exact point-in-polygon + altitude-band test

- **Horizontal:** no radius parameter. Ownship's lat/lon either falls inside a sector polygon or
  it doesn't -- the polygon boundary itself *is* the distance criterion, at whatever irregular
  shape VATGlasses defines. No buffer/inset is added to the polygon itself.
- **Vertical:** exact band containment against the matched level's `min`/`max`, using whichever
  of pressure-altitude-FL / QNH-true-altitude-FL applies (QNH-corrected below
  `VatGlassesSectorLookup.TransitionLevelFallbackFl` = FL100, pressure altitude at/above it -- see
  "Pressure altitude and QNH" below). No altitude buffer at the band edges either. **Exception:
  bucket 6d's CTR containment ignores this entirely** (horizontal-only, no vertical check at all)
  -- see that row's note for why (real-world top-down coverage vs. the nominal published floor).
- **Heading/track:** not a factor. A containment test has no notion of "closing in." Also not
  used to bias which of several overlapping matches wins -- `VatGlassesOwnershipResolver.
  ResolveOnlineControllers` returns *every* distinct online controller matching any position in
  the chain, not just the first (fixed 2026-07-28: several same-FIR CTR positions can share an
  identical prefix/type with nothing to disambiguate them, e.g. Sweden Control's
  M2/M4/M5/M6/M7/M8/MY all being "ESMM"+CTR -- returning only the first match silently picked the
  wrong one on a real flight when two such positions were online at once). Callers feed every
  returned candidate into the existing `IsNext`/`IsLikelyNext` tie-detection instead of the
  resolver guessing which one is "right."

### Prediction (bucket 7c's APP "entering", bucket 8a's CTR "converging") -- heading/route and vertical trend are the whole point

- **Horizontal:** preferred is the remaining SimBrief route legs from current position onward,
  intersected against the polygon (`VatGlassesSectorLookup.DistanceToPolygonAlongRouteNm`) --
  converging means a leg actually crosses it within `RouteApproachMaxNauticalMiles` (150nm).
  Falls back to a ray cast along current heading (`DistanceToPolygonAlongHeadingNm`), capped at
  `LateralApproachMaxNauticalMiles` (100nm), whenever no SimBrief route is loaded. The
  route-based check is steadier through a turn shortly before the boundary than instantaneous
  heading is -- heading alone can flip off right as the aircraft banks into a turn, even though
  the filed route still clearly enters the sector on the next leg.
- **Vertical (CTR, bucket 8a):** not a static band check but *satisfied-or-converging* -- already
  within the band counts regardless of level/climbing/descending (no margin needed), or a
  sustained climb/descent of at least `VerticalTrendThresholdFpm` (500fpm) for
  `VerticalTrendSustainWindow` (5s) bringing ownship within 5000ft of the band edge it's headed
  toward (widened from the pre-#18 2000ft -- a fast-climbing/descending bizjet can close that gap
  quickly). Level flight outside the band never converges, regardless of proximity.
- **Vertical (APP, bucket 7c):** decided -- no satisfied-or-converging vertical trend check at all.
  Bucket 7b's ceiling (the sector's own upper FL + 5000ft, else a flat FL290 fallback) is the only
  vertical gate; "entering" for 7c is purely lateral route/heading convergence. Simpler than CTR's
  8a on purpose -- APP/DEP is a much tighter-range, shorter-lived phase than an enroute Center
  prediction, not worth the same trend-tracking machinery.
- **Combining:** a sector counts as converging when it is not already contained/otherwise resolved
  AND both axes are at least "satisfied-or-converging" AND at least one axis is actually in the
  *converging* (not-yet-inside) state.
- **Tie-banding:** candidates are walked nearest-first; the single closest becomes the band anchor,
  and every candidate within `anchor x 1.10` of it ties with it (confident `IsNext` if it's alone,
  `IsLikelyNext` for the whole group if not) -- see bucket 8b. Replaces the old strict
  closest-only rule, which would've missed genuinely-tied cases like two adjacent sectors at
  nearly the same distance.

### Diversion invalidates the filed route

The "remaining SimBrief route legs" horizontal check above assumes the filed route is still where
the flight is actually going. A controller-issued diversion breaks that: the effective destination
(`vatsimPilot?.Arrival ?? flightPlan.Destination`) updates correctly and immediately when it
changes, so route-match/highlighting elsewhere already re-targets the new destination's own
stations fine -- but `flightPlan.Waypoints` is still whatever route was filed for the *original*
destination, which would otherwise keep projecting bucket 8's CTR prediction through a stale leg. Once a
destination change is observed mid-session, a one-way latch (`_routeInvalidatedByDiversion`, same
pattern as the takeoff latch) forces the remaining-waypoints list empty for the rest of the
session, falling back to the heading-ray-cast prediction instead. Deliberately does not attempt to
pick up a SimBrief alternate route -- in practice, a real diversion is typically "direct XXXX to
get you out of the way," not a re-route along the filed alternate, which many controllers can't
even see.

### Pressure altitude and QNH

VATGlasses sector bands are consistently FL-unit numbers, but real-world airspace near the ground
is QNH-altitude-referenced, not standard-pressure-referenced, below the (region-dependent)
transition altitude. `OwnshipTelemetry` carries both `PressureAltitudeFeet` (SimConnect's
standard 29.92"/1013.25hPa-referenced `PRESSURE ALTITUDE`) and `SeaLevelPressureHpa` (the sim's
actual local QNH, via `SEA LEVEL PRESSURE` -- independent of the pilot's altimeter Kohlsman
subscale). `PressureAltitude.QnhTrueAltitudeFeet` converts the two into a QNH-true AMSL altitude,
used for any sector level whose `max` is at/below `TransitionLevelFallbackFl` (FL100 --
a placeholder, since VATGlasses doesn't carry real per-region transition altitude/level data).

### Flapping protection

Numeric radius/tie-band thresholds (6b/6c's radius fallback, 7b's highlight radius, 8b's
tie-band) use a spatial dead-band, not a time-based one: a candidate joins at the real threshold,
but once in, only leaves once past `DeadbandExitMultiplier` (1.20) x that threshold --
`ControllerRankingModel.PassesDeadband`, with a per-check committed-callsign set pruned each tick
to whatever's still a candidate. This guards against a distance oscillating right at a boundary
(GPS/telemetry jitter, or a tie-band edge) without needing per-tick timing state. Chosen over a
12s-window time-based hysteresis (the pre-issue-#18 design's `ApplyDistanceHysteresis`/
`ApplyVatGlassesHysteresis` approach, still used as-is for bucket 9's plain distance-leader
fallback) because a spatial buffer is simpler to reason about for a numeric threshold that can be
crossed from either direction, and doesn't need timestamp bookkeeping per candidate.

**Not covered**: actual polygon containment (6b/6c/6d's preferred path, 8a's satisfied check) has
no natural "how far past the edge" distance to build a spatial dead-band from -- that would need a
real nearest-point-on-polygon-boundary primitive, which this codebase doesn't have yet (see
`ResolveAppDistanceNm`'s doc comment on the bounding-box approximation it uses instead). Flapping
right on a polygon edge is a known, accepted gap for now, not attempted here.

8a's sustained-vertical-trend requirement (5s) already serves the same debouncing purpose for the
converging/entering prediction independent of the above -- no separate dead-band needed there.

## Explicitly out of scope (issue #9)

- `topdown[]` runway-specific override objects are skipped on parse -- only the plain-string
  chain entries are used.
- Any UI/map rendering of sector boundaries.
- Manual override/pin (`SetPinnedController`) is untouched by VATGlasses resolution -- it only
  affects the *automatic* next-candidate tier. Pin itself is a separate, independent ranking
  bucket now (see "Sort order" above) -- it no longer forces `IsCurrent`/the tuned slot either
  (issue #17).
