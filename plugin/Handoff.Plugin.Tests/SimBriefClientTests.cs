using Xunit;

namespace Handoff.Plugin.Tests
{
    public class SimBriefClientTests
    {
        [Fact]
        public void ParseFlightPlan_ReadsTheFieldsIncludingAircraftTypeAndSelcal()
        {
            var json = "{\"atc\":{\"callsign\":\"FDX82M\"},\"origin\":{\"icao_code\":\"LOWW\"},\"destination\":{\"icao_code\":\"KJFK\"}," +
                       "\"alternate\":{\"icao_code\":\"KBOS\"},\"aircraft\":{\"icaocode\":\"MD11\",\"selcal\":\"ABCD\"}}";

            var plan = SimBriefClient.ParseFlightPlan(json);

            Assert.Equal("FDX82M", plan.Callsign);
            Assert.Equal("LOWW", plan.Origin);
            Assert.Equal("MD11", plan.AircraftType);
            Assert.Equal("ABCD", plan.Selcal);
        }

        [Fact]
        public void ParseFlightPlan_EmptySelcalObject_StillGivesAPlanWithNoSelcal()
        {
            // What SimBrief really returns for a plan with no SELCAL (its XML-to-JSON turns the empty
            // element into {}); the cast to string used to throw and the whole plan was dropped.
            var json = "{\"atc\":{\"callsign\":\"FDX82M\"},\"origin\":{\"icao_code\":\"LOWW\"},\"destination\":{\"icao_code\":\"KJFK\"}," +
                       "\"aircraft\":{\"icaocode\":\"MD11\",\"selcal\":{}}}";

            var plan = SimBriefClient.ParseFlightPlan(json);

            Assert.NotNull(plan);
            Assert.Equal("FDX82M", plan.Callsign);
            Assert.Equal("MD11", plan.AircraftType);
            Assert.Null(plan.Selcal);
        }

        [Theory]
        [InlineData("\"selcal\":\"\"")]
        [InlineData("\"selcal\":[]")]
        [InlineData("\"selcal\":null")]
        [InlineData("\"other\":1")]
        public void ParseFlightPlan_BlankOrOddlyShapedSelcal_IsNull(string selcalMember)
        {
            var plan = SimBriefClient.ParseFlightPlan("{\"atc\":{\"callsign\":\"X\"},\"aircraft\":{\"icaocode\":\"A320\"," + selcalMember + "}}");

            Assert.Equal("X", plan.Callsign);
            Assert.Null(plan.Selcal);
        }

        [Fact]
        public void ParseWaypoints_ParsesOrderedNavlogFixes()
        {
            var json = @"{
                ""navlog"": {
                    ""fix"": [
                        {""ident"": ""WPT1"", ""pos_lat"": ""48.1100"", ""pos_long"": ""16.5700""},
                        {""ident"": ""WPT2"", ""pos_lat"": ""49.0000"", ""pos_long"": ""17.0000""}
                    ]
                }
            }";

            var waypoints = SimBriefClient.ParseWaypoints(json);

            Assert.Equal(2, waypoints.Count);
            Assert.Equal("WPT1", waypoints[0].Ident);
            Assert.Equal(48.11, waypoints[0].Latitude, 3);
            Assert.Equal(16.57, waypoints[0].Longitude, 3);
            Assert.Equal("WPT2", waypoints[1].Ident);
        }

        [Fact]
        public void ParseWaypoints_MissingNavlog_ReturnsEmptyList()
        {
            var waypoints = SimBriefClient.ParseWaypoints("{}");
            Assert.Empty(waypoints);
        }

        [Fact]
        public void ParseWaypoints_SkipsEntriesMissingLatLon()
        {
            var json = @"{
                ""navlog"": {
                    ""fix"": [
                        {""ident"": ""WPT1"", ""pos_lat"": ""48.11"", ""pos_long"": ""16.57""},
                        {""ident"": ""BADFIX""}
                    ]
                }
            }";

            var waypoints = SimBriefClient.ParseWaypoints(json);

            Assert.Single(waypoints);
            Assert.Equal("WPT1", waypoints[0].Ident);
        }

        [Fact]
        public void ParseOriginCoordinates_ParsesLatLon()
        {
            var json = @"{""origin"": {""pos_lat"": ""50.0379"", ""pos_long"": ""8.5622""}}";

            var (lat, lon) = SimBriefClient.ParseOriginCoordinates(json);

            Assert.NotNull(lat);
            Assert.NotNull(lon);
            Assert.Equal(50.0379, lat.GetValueOrDefault(), 3);
            Assert.Equal(8.5622, lon.GetValueOrDefault(), 3);
        }

        [Fact]
        public void ParseOriginCoordinates_MissingOrigin_ReturnsNulls()
        {
            var (lat, lon) = SimBriefClient.ParseOriginCoordinates("{}");

            Assert.Null(lat);
            Assert.Null(lon);
        }

        [Fact]
        public void ParseOriginCoordinates_MalformedValue_ReturnsNullsWithoutThrowing()
        {
            var json = @"{""origin"": {""pos_lat"": ""not-a-number"", ""pos_long"": ""8.5622""}}";

            var (lat, lon) = SimBriefClient.ParseOriginCoordinates(json);

            Assert.Null(lat);
            Assert.Null(lon);
        }
    }
}
