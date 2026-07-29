using System;
using System.IO;
using System.Threading.Tasks;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatSpyStationNamingTests
    {
        private const string BoundariesJson = @"{
            ""features"": [
                { ""properties"": { ""id"": ""LOWW"" }, ""geometry"": { ""type"": ""MultiPolygon"", ""coordinates"": [[[[16,48],[17,48],[17,49],[16,48]]]] } },
                { ""properties"": { ""id"": ""EDMM-ZUG"" }, ""geometry"": { ""type"": ""MultiPolygon"", ""coordinates"": [[[[11,47],[12,47],[12,48],[11,47]]]] } },
                { ""properties"": { ""id"": ""ADR"" }, ""geometry"": { ""type"": ""MultiPolygon"", ""coordinates"": [[[[16,42],[17,42],[17,43],[16,42]]]] } },
                { ""properties"": { ""id"": ""LJLA"" }, ""geometry"": { ""type"": ""MultiPolygon"", ""coordinates"": [[[[14,46],[15,46],[15,47],[14,46]]]] } }
            ]
        }";

        private const string VatSpyDat = @"
[Countries]
Austria|LO|Radar
Germany|ED|Radar
TestCountry|AD|Radar

[Airports]
EGLL|Heathrow|51.4775|-0.4614|LHR|EGTT|0

[FIRs]
LOWW|Vienna||LOWW
EDMM-ZUG|Muenchen ACC (Zugspitze) - Muenchen|EDMM_ZUG|EDMM-ZUG
ADR|Adria Radar||ADR
LJLA|Ljubljana||LJLA
";

        private static VatSpyDataModel CreateModel()
        {
            var model = new VatSpyDataModel(
                new OperationProgressModel(),
                cacheDirectory: Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()),
                fetchLatestSha: () => Task.FromResult("sha1"),
                fetchBoundariesJson: () => Task.FromResult(BoundariesJson),
                fetchVatSpyDat: () => Task.FromResult(VatSpyDat));
            model.SyncAsync().GetAwaiter().GetResult();
            return model;
        }

        [Fact]
        public void ComposeDisplayName_CtrWithCountryOverride_UsesCountrySuffix()
        {
            Assert.Equal("Vienna Radar", VatSpyStationNaming.ComposeDisplayName("LOWW_CTR", CreateModel()));
        }

        [Fact]
        public void ComposeDisplayName_CtrSubSector_ExtractsParentPlaceNameAfterDash()
        {
            Assert.Equal("Muenchen Radar", VatSpyStationNaming.ComposeDisplayName("EDMM_ZUG_CTR", CreateModel()));
        }

        [Fact]
        public void ComposeDisplayName_CtrNoCountryOverride_UsesDefaultCenterSuffix()
        {
            Assert.Equal("Ljubljana Center", VatSpyStationNaming.ComposeDisplayName("LJLA_CTR", CreateModel()));
        }

        [Fact]
        public void ComposeDisplayName_PlaceNameAlreadyEndsWithSuffix_DoesNotDuplicateIt()
        {
            // "Adria Radar" + country-suffix "Radar" would otherwise double up to "Adria Radar Radar".
            Assert.Equal("Adria Radar", VatSpyStationNaming.ComposeDisplayName("ADR_CTR", CreateModel()));
        }

        [Fact]
        public void ComposeDisplayName_TowerTier_UsesAirportNamePlusFixedSuffix()
        {
            Assert.Equal("Heathrow Tower", VatSpyStationNaming.ComposeDisplayName("EGLL_TWR", CreateModel()));
        }

        [Fact]
        public void ComposeDisplayName_ApproachVsDeparture_ReadsFromCallsignsOwnToken()
        {
            Assert.Equal("Heathrow Approach", VatSpyStationNaming.ComposeDisplayName("EGLL_APP", CreateModel()));
            Assert.Equal("Heathrow Departure", VatSpyStationNaming.ComposeDisplayName("EGLL_DEP", CreateModel()));
        }

        [Fact]
        public void ComposeDisplayName_OtherTier_ReturnsNull()
        {
            Assert.Null(VatSpyStationNaming.ComposeDisplayName("EGLL_ATIS", CreateModel()));
        }

        [Fact]
        public void ComposeDisplayName_UnknownAirportPrefix_ReturnsNull()
        {
            Assert.Null(VatSpyStationNaming.ComposeDisplayName("XXXX_TWR", CreateModel()));
        }

        [Fact]
        public void ComposeDisplayName_CtrPrefixNotInVatSpyData_ReturnsNull()
        {
            Assert.Null(VatSpyStationNaming.ComposeDisplayName("ZZZZ_CTR", CreateModel()));
        }
    }
}
