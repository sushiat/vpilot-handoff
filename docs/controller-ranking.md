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
| `IsCurrent` | any | Tuned COM1/COM2 frequency matches, or manually pinned via `SetPinnedController` | Always rank 0. |
| `IsContactMe` | any | Callsign present in `ContactMeModel.ActiveCallsigns` | Ranked below current, above SELCAL/next-candidate. |
| (SELCAL ordering, no dedicated flag) | any | Callsign present in `SelcalActiveModel.ActiveCallsigns` | Ranked below contact-me, above next-candidate. |
| `IsLikelyNextCandidate` | DEL/GND/TWR/APP/DEP | Priority order: (1) VATGlasses sector/airport-topdown resolution -- ownship's lat/lon + altitude falls inside a VATGlasses sector polygon (or, on the ground, the flight-plan airport's `topdown[]` chain) whose resolved online controller is in this tier; (2) else callsign-prefix route match against origin (pre-takeoff)/destination (post-takeoff); (3) else, only when no flight plan is loaded at all, the tier's single closest-by-distance controller | Only the *first* qualifying tier walking up the chain from current tier gets flagged. |
| `IsLikelyNextCandidate` | CTR | VATGlasses sector resolution only (no distance/route fallback -- CTR never got a no-flight-plan proximity fallback here) | Still gated on being an actual next-tier-walk match, not just proximity. |
| `IsApproaching` | GND | Always `false` | Ground never gets this flag -- Tower is the lowest tier it applies to (a UNICOM aircraft taxiing isn't "approaching" Ground, it's already there). |
| `IsApproaching` | TWR | Airborne, within `TowerApproachingNauticalMiles` (20nm) | |
| `IsApproaching` | APP/DEP | Airborne, <= `AppOmnidirectionalNauticalMiles` (40nm) any heading, or <= `AppOuterNauticalMiles` (50nm) with heading within `AppHeadingToleranceDegrees` (45 degrees) of bearing to station -- OR a VATGlasses convergence match (see below) when coverage exists | VATGlasses convergence is preferred when it produces a result, but the fixed-radius heuristic still applies independently as a fallback. |
| `IsApproaching` | CTR | VATGlasses lateral+vertical convergence against a resolved-online sector -- not already contained (that's `IsLikelyNextCandidate` instead) AND both axes satisfied-or-converging AND at least one actually converging. Only the single *closest* qualifying sector is ever flagged, not every sector within the lookahead cap. | No fallback for uncovered regions -- stays `false` there. |
| `IsApproaching` | DEL/Other | Always `false` | |
| `IsApproaching` (any tier) | -- | Always `false` whenever something is already `IsCurrent` (tuned/pinned) | This flag only means something pre-contact. |
| `IsHighlighted` | `_ATIS` (parses to `Other`) | Callsign ICAO-prefix-matches the route airport | |
| `IsHighlighted` (any other tier) | -- | Always `false` | The old fixed-radius CTR highlight heuristic was removed entirely (issue #9) -- a CTR only stands out now via `IsLikelyNextCandidate`, a stronger signal, when VATGlasses resolves it. |

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
- Manual override/pin (`SetPinnedController`) is untouched -- VATGlasses resolution only affects
  the *automatic* next-candidate tier, never the pinned/tuned-current slot.
