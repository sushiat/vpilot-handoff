# Controller ranking: flags and criteria

`ControllerRankingModel` (see its own class doc-comment for the full tiebreak stack) exposes six
booleans on each `RankedController`. This doc is the detailed reference for what sets each one --
kept out of the class doc-comment itself since the full table is long. See issue #8 for the
original tier-chain/route-match/distance design and issue #9 for the VATGlasses sector/boundary
geometry that upgrades several of these rows.

## Design principle: `IsLikelyNextCandidate` vs `IsApproaching`

These are two different kinds of signal, not the same thing at different thresholds:

- **`IsLikelyNextCandidate`** is the rough estimate -- whoever's next in the DEL->CTR chain, best
  guess from route-match/proximity, or (with VATGlasses coverage) exact current sector/airport
  containment.
- **`IsApproaching`** is the predictive, data-driven one -- "not there yet, but the geometry
  (heading, polygon edges, sustained climb/descent trend, altitude-band edges) says you're headed
  toward it."

Containment (already inside a sector) belongs to `IsLikelyNextCandidate`, not `IsApproaching` --
it isn't a prediction, it already happened.

## Flag/criteria table

| Flag | Tier(s) | Trigger criteria | Notes |
|---|---|---|---|
| `IsCurrent` | any | Tuned COM1 or COM2 frequency matches | Always ranked first. Since issue #17, COM1 and COM2 can each independently match a different real online station -- both get `IsCurrent` simultaneously, not just whichever a single lookup found first. Manual pin (`SetPinnedController`) no longer sets this (see "Sort order" below) -- it used to, but pinning a controller while a different one was genuinely tuned wrongly stole `IsCurrent`/TUNED status from the real one. |
| `IsContactMe` | any | Callsign present in `ContactMeModel.ActiveCallsigns` | Ranked below current, above SELCAL/next-candidate. |
| (SELCAL ordering, no dedicated flag) | any | Callsign present in `SelcalActiveModel.ActiveCallsigns` | Ranked below contact-me, above next-candidate. |
| `IsLikelyNextCandidate` | DEL/GND/TWR/APP/DEP | Priority order: (1) VATGlasses sector/airport-topdown resolution -- ownship's lat/lon + altitude falls inside a VATGlasses sector polygon (or, on the ground, the flight-plan airport's `topdown[]` chain) whose resolved online controller is in this tier; (2) else callsign-prefix route match against origin (pre-takeoff)/destination (post-takeoff); (3) else, only when no flight plan is loaded at all, the tier's single closest-by-distance controller | Only the *first* qualifying tier walking up the chain from current tier gets flagged. |
| `IsLikelyNextCandidate` | CTR | VATGlasses sector resolution only (no distance/route fallback -- CTR never got a no-flight-plan proximity fallback here) | Still gated on being an actual next-tier-walk match, not just proximity. |
| `IsApproaching` | GND | Always `false` | Ground never gets this flag -- Tower is the lowest tier it applies to (a UNICOM aircraft taxiing isn't "approaching" Ground, it's already there). |
| `IsApproaching` | TWR | Airborne, within `TowerApproachingNauticalMiles` (20nm) | |
| `IsApproaching` | APP/DEP | Airborne, <= `AppOmnidirectionalNauticalMiles` (40nm) any heading, or <= `AppOuterNauticalMiles` (50nm) with heading within `AppHeadingToleranceDegrees` (45 degrees) of bearing to station -- OR a VATGlasses convergence match (see below) when coverage exists | VATGlasses convergence is preferred when it produces a result, but the fixed-radius heuristic still applies independently as a fallback. |
| `IsApproaching` | CTR | VATGlasses lateral+vertical convergence against a resolved-online sector -- not already contained (that's `IsLikelyNextCandidate` instead) AND both axes satisfied-or-converging AND at least one actually converging. Only the single *closest* qualifying sector is ever flagged, not every sector within the lookahead cap. | No fallback for uncovered regions -- stays `false` there. |
| `IsApproaching` | DEL/Other | Always `false` | |
| `IsApproaching` (any tier) | -- | Always `false` whenever something is already `IsCurrent` (tuned) | This flag only means something pre-contact. |
| `IsHighlighted` | `_ATIS` (parses to `Other`) | Callsign ICAO-prefix-matches the route airport | Since issue #17, `IsHighlighted` (like `IsApproaching`) is also pulled ahead of unrelated stations in the sort order, regardless of tier -- see "Sort order" below. |
| `IsHighlighted` (any other tier) | -- | Always `false` | The old fixed-radius CTR highlight heuristic was removed entirely (issue #9) -- a CTR only stands out now via `IsLikelyNextCandidate`, a stronger signal, when VATGlasses resolves it. |

## Sort order

The Android client renders the list in exactly the order the plugin sends it -- no client-side
re-sorting. As of issue #17's flight-test fixes, `ControllerRankingModel.Recompute()` builds that
order as a sequence of **buckets** (numbered here as `bucket#` for easy reference elsewhere --
deliberately not "tier," which already means the DEL/GND/TWR/APP/CTR chain):

- **Bucket 1 -- Current** (`IsCurrent`, tuned) -- can be more than one row if COM1 and COM2 are
  each tuned to a different real station.
- **Bucket 2 -- Standby-tuned** -- a controller's frequency is currently loaded into COM1 or COM2
  *standby*, ready to swap to active the moment a handoff comes. Not a `RankedController` boolean
  field -- Android computes this locally from the `radioState` message's standby frequencies (same
  way it computes pin in bucket 5), since the plugin only needs it internally to decide ranking
  position, not to expose a new protocol field for it. Gets its own STBY badge on Android.
- **Bucket 3 -- Contact-me** (`IsContactMe`).
- **Bucket 4 -- SELCAL** (no dedicated flag -- see the flag table above).
- **Bucket 5 -- Pinned** (`SetPinnedController`) -- a deliberate bookmark, kept prominent but never
  a stand-in for bucket 1/`IsCurrent` (see that row's note above). Like standby, not its own
  `RankedController` boolean -- Android compares each row's callsign against its own
  locally-tracked pinned callsign.
- **Bucket 6 -- `IsApproaching`** -- ranked *above* bucket 7 (`IsLikelyNextCandidate`), even though
  the next candidate is nominally "more actionable": flight-test feedback found a converging
  station reads as more immediately relevant in practice than the rough next-tier guess.
- **Bucket 7 -- `IsLikelyNextCandidate`**.
- **Bucket 8 -- `IsHighlighted`** -- ranked *below* bucket 7, unlike bucket 6 above it. A much
  softer "worth a glance" signal (see its own row in the flag table) that should only ever outrank
  a wholly unrelated station, never the actual next candidate.
- **Bucket 9 -- Everything else.**

Buckets 2-9 are each internally ordered by chain tier then route-match/distance.

Before issue #17, `IsHighlighted`/`IsApproaching` (buckets 6/8) were computed only for Android's
color/badge display and had zero effect on order at all -- a converging CTR or a route-matching
ATIS could sort behind an entire page of wholly unrelated stations, since chain-tier bucketing
alone decided position. Pin (bucket 5), meanwhile, used to be folded directly into bucket 1/
`IsCurrent` (see that row's note above) rather than having its own bucket.

A controller-issued diversion (the VATSIM-filed destination changing mid-session) also affects
`IsApproaching`'s route-projected prediction, not just sort order directly -- see "Diversion
invalidates the filed route" below.

## VATGlasses match parameters: distance / altitude / heading

Two distinct checks, matching the design principle above -- heading is deliberately irrelevant to
one and central to the other.

### Containment (`IsLikelyNextCandidate`) -- exact point-in-polygon + altitude-band test

- **Horizontal:** no radius parameter. Ownship's lat/lon either falls inside a sector polygon or
  it doesn't -- the polygon boundary itself *is* the distance criterion, at whatever irregular
  shape VATGlasses defines. No buffer/inset is added to the polygon itself.
- **Vertical:** exact band containment against the matched level's `min`/`max`, using whichever
  of pressure-altitude-FL / QNH-true-altitude-FL applies (QNH-corrected below
  `VatGlassesSectorLookup.TransitionLevelFallbackFl` = FL100, pressure altitude at/above it -- see
  "Pressure altitude and QNH" below). No altitude buffer at the band edges either.
- **Heading/track:** not a factor. A containment test has no notion of "closing in." Also not
  used to bias *which* of several overlapping matches wins (`VatGlassesOwnershipResolver` picks
  the first chain entry that resolves to an online controller, not the "most converged-upon" one).

### Prediction (`IsApproaching`) -- heading/route and vertical trend are the whole point

- **Horizontal:** preferred is the remaining SimBrief route legs from current position onward,
  intersected against the polygon (`VatGlassesSectorLookup.DistanceToPolygonAlongRouteNm`) --
  converging means a leg actually crosses it within `RouteApproachMaxNauticalMiles` (150nm).
  Falls back to a ray cast along current heading (`DistanceToPolygonAlongHeadingNm`), capped at
  `LateralApproachMaxNauticalMiles` (100nm), whenever no SimBrief route is loaded. The
  route-based check is steadier through a turn shortly before the boundary than instantaneous
  heading is -- heading alone can flip off right as the aircraft banks into a turn, even though
  the filed route still clearly enters the sector on the next leg.
- **Vertical:** not a static band check but a sustained-trend one -- a climb/descent of at least
  `VerticalTrendThresholdFpm` (500fpm) sustained for `VerticalTrendSustainWindow` (5s), bringing
  ownship within `VerticalApproachThresholdFeet` (2000ft) of the band edge it's headed toward.
  Level flight outside the band never converges, regardless of proximity.
- **Combining:** a sector counts as `IsApproaching` when it is not already the resolved
  current/next-candidate match AND both axes are at least "satisfied-or-converging" AND at least
  one axis is actually in the *converging* (not-yet-inside) state.
- **Closest-next-wins:** candidate sectors are walked nearest-first, and only the single closest
  qualifying one is ever flagged -- not every sector within the lookahead cap. Flying straight
  across a whole FIR (e.g. north to south over Austria) would otherwise flag both the near and
  far sector at once; real airspace is a sequence of adjacent sectors along the path, so only one
  is ever genuinely "next."

### Diversion invalidates the filed route

The "remaining SimBrief route legs" horizontal check above assumes the filed route is still where
the flight is actually going. A controller-issued diversion breaks that: the effective destination
(`vatsimPilot?.Arrival ?? flightPlan.Destination`) updates correctly and immediately when it
changes, so route-match/highlighting elsewhere already re-targets the new destination's own
stations fine -- but `flightPlan.Waypoints` is still whatever route was filed for the *original*
destination, which would otherwise keep projecting `IsApproaching` through a stale leg. Once a
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

Both a containment edge and a prediction's lost/regained convergence need the same kind of
protection `ApplyDistanceHysteresis` already gives the per-tier distance leader -- a committed
value that only changes after being consistently different for the full 12s `HysteresisWindow`.
`ApplyVatGlassesHysteresis` gives the containment resolution (`IsLikelyNextCandidate`) that same
treatment, as a single committed value (not per-tier, since at most one sector/airport-chain
resolution is relevant at a time). `IsApproaching`'s own convergence result doesn't need a
separate commit/challenger slot -- it's already gated by the 5s sustained vertical-trend
requirement, which serves the same debouncing purpose.

## Explicitly out of scope (issue #9)

- `topdown[]` runway-specific override objects are skipped on parse -- only the plain-string
  chain entries are used.
- Any UI/map rendering of sector boundaries.
- Manual override/pin (`SetPinnedController`) is untouched by VATGlasses resolution -- it only
  affects the *automatic* next-candidate tier. Pin itself is a separate, independent ranking
  bucket now (see "Sort order" above) -- it no longer forces `IsCurrent`/the tuned slot either
  (issue #17).
