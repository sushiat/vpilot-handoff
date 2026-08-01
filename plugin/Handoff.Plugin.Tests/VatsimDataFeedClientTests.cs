using System.Linq;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatsimDataFeedClientTests
    {
        [Fact]
        public void ParseControllers_FiltersOutObservers()
        {
            var json = @"{
                ""controllers"": [
                    {""callsign"": ""JOHN_OBS"", ""cid"": 1, ""name"": ""John Smith"", ""facility"": 0, ""rating"": 3},
                    {""callsign"": ""EGLL_TWR"", ""cid"": 2, ""name"": ""Jane Doe"", ""facility"": 4, ""rating"": 5}
                ]
            }";

            var controllers = VatsimDataFeedClient.ParseControllers(json);

            var controller = Assert.Single(controllers);
            Assert.Equal("EGLL_TWR", controller.Callsign);
        }

        [Fact]
        public void ParseControllers_ParsesAllFields()
        {
            var json = @"{
                ""controllers"": [
                    {""callsign"": ""EGLL_TWR"", ""cid"": 1234567, ""name"": ""Jane Doe"", ""facility"": 4, ""rating"": 5}
                ]
            }";

            var controller = VatsimDataFeedClient.ParseControllers(json).Single();

            Assert.Equal("EGLL_TWR", controller.Callsign);
            Assert.Equal(1234567, controller.Cid);
            Assert.Equal("Jane Doe", controller.Name);
            Assert.Equal(4, controller.Facility);
            Assert.Equal(5, controller.Rating);
        }

        [Fact]
        public void ParseControllers_NoControllersArray_ReturnsEmpty()
        {
            var controllers = VatsimDataFeedClient.ParseControllers("{}");

            Assert.Empty(controllers);
        }

        [Fact]
        public void ParseControllers_EmptyControllersArray_ReturnsEmpty()
        {
            var controllers = VatsimDataFeedClient.ParseControllers(@"{""controllers"": []}");

            Assert.Empty(controllers);
        }

        [Fact]
        public void ParseControllers_MissingCallsign_IsSkipped()
        {
            var json = @"{""controllers"": [{""cid"": 1, ""facility"": 4}]}";

            var controllers = VatsimDataFeedClient.ParseControllers(json);

            Assert.Empty(controllers);
        }

        [Fact]
        public void ParseControllers_ParsesTextAtis_MultiLine()
        {
            var json = @"{
                ""controllers"": [
                    {""callsign"": ""EGLL_TWR"", ""cid"": 1, ""facility"": 4, ""text_atis"": [""Heathrow Tower"", ""Submit feedback at vats.im/atcfb""]}
                ]
            }";

            var controller = VatsimDataFeedClient.ParseControllers(json).Single();

            Assert.Equal(new[] { "Heathrow Tower", "Submit feedback at vats.im/atcfb" }, controller.TextAtis);
        }

        [Fact]
        public void ParseControllers_MissingTextAtis_IsEmptyNotNull()
        {
            var json = @"{""controllers"": [{""callsign"": ""EGLL_TWR"", ""cid"": 1, ""facility"": 4}]}";

            var controller = VatsimDataFeedClient.ParseControllers(json).Single();

            Assert.Empty(controller.TextAtis);
        }

        [Fact]
        public void ParsePilots_ParsesFiledFlightPlan()
        {
            var json = @"{
                ""pilots"": [
                    {""callsign"": ""BAW123"", ""cid"": 1234567, ""flight_plan"": {""departure"": ""EGLL"", ""arrival"": ""KJFK""}}
                ]
            }";

            var pilot = VatsimDataFeedClient.ParsePilots(json).Single();

            Assert.Equal("BAW123", pilot.Callsign);
            Assert.Equal("EGLL", pilot.Departure);
            Assert.Equal("KJFK", pilot.Arrival);
            Assert.Equal("1234567", pilot.Cid);
        }

        [Fact]
        public void ParsePilots_MissingCid_StaysNull()
        {
            var json = @"{
                ""pilots"": [
                    {""callsign"": ""BAW123"", ""flight_plan"": {""departure"": ""EGLL"", ""arrival"": ""KJFK""}}
                ]
            }";

            var pilot = VatsimDataFeedClient.ParsePilots(json).Single();

            Assert.Null(pilot.Cid);
        }

        [Fact]
        public void ParsePilots_NoFlightPlanFiled_IsSkipped()
        {
            var json = @"{
                ""pilots"": [
                    {""callsign"": ""BAW123""},
                    {""callsign"": ""DLH456"", ""flight_plan"": null}
                ]
            }";

            var pilots = VatsimDataFeedClient.ParsePilots(json);

            Assert.Empty(pilots);
        }

        [Fact]
        public void ParsePilots_MissingCallsign_IsSkipped()
        {
            var json = @"{""pilots"": [{""flight_plan"": {""departure"": ""EGLL"", ""arrival"": ""KJFK""}}]}";

            var pilots = VatsimDataFeedClient.ParsePilots(json);

            Assert.Empty(pilots);
        }

        [Fact]
        public void ParsePilots_NoPilotsArray_ReturnsEmpty()
        {
            var pilots = VatsimDataFeedClient.ParsePilots("{}");

            Assert.Empty(pilots);
        }
    }
}
