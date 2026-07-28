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

`Bucket` refers to the numbered ranking-order buckets defined in "Sort order" below -- included
here so you don't have to cross-reference to see where a given flag lands in the list. Bucket 6
(below) is split into lettered sub-rows (6a-6e) since its criteria don't fit one row -- they're
checked in that order (6a first) to build the `IsHighlighted` set, then 6e resolves `IsNext`/
`IsLikelyNext` from within it. **Bucket 6 supersedes the old `IsApproaching`/`IsLikelyNextCandidate`/
ATIS-only-`IsHighlighted` design entirely** (issue #18 redesign, 2026-07-28) -- `IsNext`/
`IsLikelyNext` are new flags replacing `IsLikelyNextCandidate`, and `IsHighlighted` is no longer
ATIS-specific. Bucket 7's exact final ranking position (and whether `IsNext`/`IsLikelyNext` occupy
one bucket or two) is not decided yet -- see "Sort order" below, still stale/pending for buckets
6-8.

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

## Sort order

The Android client renders the list in exactly the order the plugin sends it -- no client-side
re-sorting. As of issue #17's flight-test fixes, `ControllerRankingModel.Recompute()` builds that
order as a sequence of **buckets** (numbered here as `bucket#` for easy reference elsewhere --
deliberately not "tier," which already means the DEL/GND/TWR/APP/CTR chain):

- **Bucket 1 -- Current** (`IsCurrent`, tuned) -- can be more than one row if COM1 and COM2 are
  each tuned to a different real station, with COM1's always ordered ahead of COM2's.
- **Bucket 2 -- `IsStandbyTuned`** -- a controller's frequency is currently loaded into COM1 or
  COM2 *standby*, ready to swap to active the moment a handoff comes. Gets its own STBY badge on
  Android.
- **Bucket 3 -- Contact-me** (`IsContactMe`).
- **Bucket 4 -- `IsSelcalActive`**.
- **Bucket 5 -- Pinned** (`SetPinnedController`) -- see the flag table above for the manual-toggle
  and current/standby-overlap behavior.
- **Buckets 6-8 -- PENDING (issue #18 redesign in progress, 2026-07-28).** The old `IsApproaching`/
  `IsLikelyNextCandidate`/ATIS-only-`IsHighlighted` design these bullets described is superseded by
  the flag table's new bucket 6a-6e rows above -- but where `IsNext`/`IsLikelyNext`/`IsHighlighted`
  actually land relative to each other in final rank order (one bucket or several, and in what
  order) isn't decided yet. Do not treat the old bullets below as current -- kept only as a
  reference point for what's being replaced, delete once bucket 7 is formally addressed:
  - ~~Bucket 6 -- `IsApproaching`, ranked above bucket 7 because a converging station reads as more
    immediately relevant than the rough next-tier guess.~~
  - ~~Bucket 7 -- `IsLikelyNextCandidate`.~~
  - ~~Bucket 8 -- `IsHighlighted`, ranked below bucket 7 as a softer "worth a glance" signal.~~
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
