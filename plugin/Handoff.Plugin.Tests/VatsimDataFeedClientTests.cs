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
    }
}
