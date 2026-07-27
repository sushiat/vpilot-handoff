using Xunit;

namespace Handoff.Plugin.Tests
{
    public class SimBriefClientTests
    {
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
    }
}
