using System.Linq;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatSpyDataClientTests
    {
        [Fact]
        public void ParseBoundaryRings_ParsesMultiPolygonOuterRing_SwappingLonLatToLatLon()
        {
            var json = @"{
                ""type"": ""FeatureCollection"",
                ""features"": [
                    { ""type"": ""Feature"", ""properties"": { ""id"": ""EDWW"" },
                      ""geometry"": { ""type"": ""MultiPolygon"", ""coordinates"": [
                          [ [ [16.5, 48.1], [16.6, 48.1], [16.6, 48.2], [16.5, 48.1] ] ]
                      ] } }
                ]
            }";

            var rings = VatSpyDataClient.ParseBoundaryRings(json);

            var ring = rings["EDWW"].Single();
            Assert.Equal(4, ring.Count);
            // GeoJSON order is [lon, lat] -- expect the reverse in the parsed point.
            Assert.Equal(48.1, ring[0].Latitude);
            Assert.Equal(16.5, ring[0].Longitude);
        }

        [Fact]
        public void ParseBoundaryRings_MultiplePolygonsInOneFeature_ProducesOneRingEach()
        {
            var json = @"{
                ""features"": [
                    { ""properties"": { ""id"": ""ADR"" },
                      ""geometry"": { ""type"": ""MultiPolygon"", ""coordinates"": [
                          [ [ [1, 1], [2, 1], [2, 2], [1, 1] ] ],
                          [ [ [10, 10], [11, 10], [11, 11], [10, 10] ] ]
                      ] } }
                ]
            }";

            var rings = VatSpyDataClient.ParseBoundaryRings(json);

            Assert.Equal(2, rings["ADR"].Count);
        }

        [Fact]
        public void ParseBoundaryRings_HoleRingsAreSkipped_OnlyOuterRingKept()
        {
            var json = @"{
                ""features"": [
                    { ""properties"": { ""id"": ""X"" },
                      ""geometry"": { ""type"": ""MultiPolygon"", ""coordinates"": [
                          [ [ [0,0],[4,0],[4,4],[0,4],[0,0] ], [ [1,1],[2,1],[2,2],[1,1] ] ]
                      ] } }
                ]
            }";

            var rings = VatSpyDataClient.ParseBoundaryRings(json);

            var ring = rings["X"].Single();
            Assert.Equal(5, ring.Count); // outer ring only, the 3-point hole is dropped
        }

        [Fact]
        public void ParseBoundaryRings_MissingOrEmptyFeatures_ReturnsEmpty()
        {
            Assert.Empty(VatSpyDataClient.ParseBoundaryRings(@"{}"));
            Assert.Empty(VatSpyDataClient.ParseBoundaryRings(@"{""features"": []}"));
        }

        [Fact]
        public void ParseVatSpyDat_ParsesCountriesAirportsAndFirsSections()
        {
            var text = @"
[Countries]
;comment line, skipped
Austria|LO|Radar
USA|KZ|

[Airports]
;ICAO|Name|Lat|Lon|IATA|FIR|IsPseudo
EGLL|Heathrow|51.4775|-0.4614|LHR|EGTT|0

[FIRs]
;ICAO|NAME|CALLSIGN PREFIX|FIR BOUNDARY
EDWW|Bremen||EDWW
EDWW-ALR|Bremen ACC (Aller) - Bremen|EDWW_ALR|EDWW-ALR

[UIRs]
ADR_U|Adria Radar Upper|LJLA,LDZO
";

            var dat = VatSpyDataClient.ParseVatSpyDat(text);

            Assert.Equal("Radar", dat.CtrSuffixByCountryPrefix["LO"]);
            Assert.Equal(string.Empty, dat.CtrSuffixByCountryPrefix["KZ"]);

            var airport = dat.AirportsByIcao["EGLL"];
            Assert.Equal("Heathrow", airport.Name);

            Assert.Equal(2, dat.FirRows.Count);
            var topLevel = dat.FirRows.Single(r => r.BoundaryId == "EDWW");
            Assert.Equal("Bremen", topLevel.Name);
            // Blank CALLSIGN PREFIX defaults to the row's own ICAO column.
            Assert.Equal("EDWW", topLevel.CallsignPrefix);

            var subSector = dat.FirRows.Single(r => r.BoundaryId == "EDWW-ALR");
            Assert.Equal("EDWW_ALR", subSector.CallsignPrefix);
        }

        [Fact]
        public void ParseVatSpyDat_UnknownSections_AreIgnored()
        {
            var dat = VatSpyDataClient.ParseVatSpyDat("[IDL]\n90.0|-180.0\n");

            Assert.Empty(dat.CtrSuffixByCountryPrefix);
            Assert.Empty(dat.AirportsByIcao);
            Assert.Empty(dat.FirRows);
        }
    }
}
