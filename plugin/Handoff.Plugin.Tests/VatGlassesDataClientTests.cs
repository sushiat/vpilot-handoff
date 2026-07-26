using System.Linq;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatGlassesDataClientTests
    {
        [Fact]
        public void ParseRegionFile_ParsesAirportTopdownChain()
        {
            var json = @"{
                ""airports"": {
                    ""LOWI"": {""topdown"": [""AI"", ""IEA"", ""VCL""]}
                },
                ""airspace"": [],
                ""positions"": {}
            }";

            var region = VatGlassesDataClient.ParseRegionFile(json);

            var airport = region.Airports["LOWI"];
            Assert.Equal("LOWI", airport.Icao);
            Assert.Equal(new[] { "AI", "IEA", "VCL" }, airport.Topdown);
        }

        [Fact]
        public void ParseRegionFile_ParsesAirspaceSectorWithLevelsAndPoints()
        {
            var json = @"{
                ""airports"": {},
                ""airspace"": [
                    {
                        ""id"": ""VCL"",
                        ""group"": ""LOWI_APP"",
                        ""owner"": [""VCL"", ""VC""],
                        ""sectors"": [
                            {
                                ""min"": 0,
                                ""max"": 245,
                                ""points"": [
                                    {""lat"": ""475026"", ""lng"": ""0124429""},
                                    {""lat"": ""474000"", ""lng"": ""0123000""}
                                ]
                            }
                        ]
                    }
                ],
                ""positions"": {}
            }";

            var sector = VatGlassesDataClient.ParseRegionFile(json).Airspace.Single();

            Assert.Equal("VCL", sector.Id);
            Assert.Equal("LOWI_APP", sector.Group);
            Assert.Equal(new[] { "VCL", "VC" }, sector.Owner);
            var level = sector.Levels.Single();
            Assert.Equal(0, level.MinFlightLevel);
            Assert.Equal(245, level.MaxFlightLevel);
            Assert.Equal(2, level.Points.Count);
            Assert.Equal("475026", level.Points[0].LatitudeDms);
            Assert.Equal("0124429", level.Points[0].LongitudeDms);
        }

        [Fact]
        public void ParseRegionFile_ParsesPositions()
        {
            var json = @"{
                ""airports"": {},
                ""airspace"": [],
                ""positions"": {
                    ""VCL"": {""type"": ""APP"", ""frequency"": ""127.900"", ""callsign"": ""Innsbruck Radar"", ""pre"": ""LOWI""}
                }
            }";

            var position = VatGlassesDataClient.ParseRegionFile(json).Positions["VCL"];

            Assert.Equal("VCL", position.Id);
            Assert.Equal("APP", position.Type);
            Assert.Equal("127.900", position.Frequency);
            Assert.Equal("Innsbruck Radar", position.Callsign);
            Assert.Equal("LOWI", position.Prefix);
        }

        [Fact]
        public void ParseRegionFile_MissingSections_ReturnsEmptyCollectionsNotNull()
        {
            var region = VatGlassesDataClient.ParseRegionFile("{}");

            Assert.Empty(region.Airports);
            Assert.Empty(region.Airspace);
            Assert.Empty(region.Positions);
        }
    }
}
