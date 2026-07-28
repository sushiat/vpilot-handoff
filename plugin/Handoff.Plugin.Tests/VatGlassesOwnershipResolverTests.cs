using System.Collections.Generic;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatGlassesOwnershipResolverTests
    {
        private static readonly Dictionary<string, VatGlassesPosition> Positions = new Dictionary<string, VatGlassesPosition>
        {
            ["AI"] = new VatGlassesPosition("AI", "CTR", "133.500", "Wien Radar", new List<string> { "LOVV" }),
            ["IEA"] = new VatGlassesPosition("IEA", "CTR", "128.000", "Innsbruck Radar", new List<string> { "LOWI" }),
        };

        [Fact]
        public void ResolveOnlineController_FirstEntryOnline_ReturnsIt()
        {
            var chain = new List<string> { "AI", "IEA" };
            var online = new List<HandoffController> { new HandoffController("LOVV_CTR", 13350, 48, 16) };

            var result = VatGlassesOwnershipResolver.ResolveOnlineController(chain, Positions, online);

            Assert.Equal("LOVV_CTR", result.Callsign);
        }

        [Fact]
        public void ResolveOnlineController_FirstOffline_FallsToSecond()
        {
            var chain = new List<string> { "AI", "IEA" };
            var online = new List<HandoffController> { new HandoffController("LOWI_CTR", 12800, 47, 11) };

            var result = VatGlassesOwnershipResolver.ResolveOnlineController(chain, Positions, online);

            Assert.Equal("LOWI_CTR", result.Callsign);
        }

        [Fact]
        public void ResolveOnlineController_NothingOnline_ReturnsNull()
        {
            var chain = new List<string> { "AI", "IEA" };
            var online = new List<HandoffController>();

            var result = VatGlassesOwnershipResolver.ResolveOnlineController(chain, Positions, online);

            Assert.Null(result);
        }

        [Fact]
        public void ResolveOnlineController_EmptyChain_ReturnsNull()
        {
            var result = VatGlassesOwnershipResolver.ResolveOnlineController(new List<string>(), Positions, new List<HandoffController> { new HandoffController("LOVV_CTR", 13350, 48, 16) });
            Assert.Null(result);
        }

        [Fact]
        public void ResolveOnlineController_OnlineButWrongTier_DoesNotMatch()
        {
            var chain = new List<string> { "AI" };
            // Online but as GND, not CTR -- shouldn't match the AI position's CTR type.
            var online = new List<HandoffController> { new HandoffController("LOVV_GND", 12100, 48, 16) };

            var result = VatGlassesOwnershipResolver.ResolveOnlineController(chain, Positions, online);

            Assert.Null(result);
        }
    }
}
