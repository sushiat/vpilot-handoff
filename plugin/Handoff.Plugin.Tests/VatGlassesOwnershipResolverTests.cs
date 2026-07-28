using System.Collections.Generic;
using System.Linq;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatGlassesOwnershipResolverTests
    {
        private static readonly Dictionary<string, VatGlassesPosition> Positions = new Dictionary<string, VatGlassesPosition>
        {
            ["AI"] = new VatGlassesPosition("AI", "CTR", "133.500", "Wien Radar", new List<string> { "LOVV" }),
            ["IEA"] = new VatGlassesPosition("IEA", "CTR", "128.000", "Innsbruck Radar", new List<string> { "LOWI" }),
            // Mirrors real VATGlasses data (issue #17's ESMM_5-vs-7 flight-test bug): several
            // positions in the same FIR sharing an identical prefix/type, with nothing in the
            // data to tell them apart by callsign or frequency.
            ["M5"] = new VatGlassesPosition("M5", "CTR", "132.765", "Sweden Control", new List<string> { "ESMM" }),
            ["M7"] = new VatGlassesPosition("M7", "CTR", "124.155", "Sweden Control", new List<string> { "ESMM" }),
        };

        [Fact]
        public void ResolveOnlineControllers_FirstEntryOnline_ReturnsIt()
        {
            var chain = new List<string> { "AI", "IEA" };
            var online = new List<HandoffController> { new HandoffController("LOVV_CTR", 13350, 48, 16) };

            var result = VatGlassesOwnershipResolver.ResolveOnlineControllers(chain, Positions, online);

            Assert.Equal("LOVV_CTR", result.Single().Callsign);
        }

        [Fact]
        public void ResolveOnlineControllers_FirstOffline_FallsToSecond()
        {
            var chain = new List<string> { "AI", "IEA" };
            var online = new List<HandoffController> { new HandoffController("LOWI_CTR", 12800, 47, 11) };

            var result = VatGlassesOwnershipResolver.ResolveOnlineControllers(chain, Positions, online);

            Assert.Equal("LOWI_CTR", result.Single().Callsign);
        }

        [Fact]
        public void ResolveOnlineControllers_NothingOnline_ReturnsEmpty()
        {
            var chain = new List<string> { "AI", "IEA" };
            var online = new List<HandoffController>();

            var result = VatGlassesOwnershipResolver.ResolveOnlineControllers(chain, Positions, online);

            Assert.Empty(result);
        }

        [Fact]
        public void ResolveOnlineControllers_EmptyChain_ReturnsEmpty()
        {
            var result = VatGlassesOwnershipResolver.ResolveOnlineControllers(new List<string>(), Positions, new List<HandoffController> { new HandoffController("LOVV_CTR", 13350, 48, 16) });
            Assert.Empty(result);
        }

        [Fact]
        public void ResolveOnlineControllers_OnlineButWrongTier_DoesNotMatch()
        {
            var chain = new List<string> { "AI" };
            // Online but as GND, not CTR -- shouldn't match the AI position's CTR type.
            var online = new List<HandoffController> { new HandoffController("LOVV_GND", 12100, 48, 16) };

            var result = VatGlassesOwnershipResolver.ResolveOnlineControllers(chain, Positions, online);

            Assert.Empty(result);
        }

        [Fact]
        public void ResolveOnlineControllers_BothAmbiguousPositionsOnline_ReturnsBoth()
        {
            // Regression (issue #17): M5 and M7 share the same prefix ("ESMM") and type ("CTR")
            // -- resolving to just the first match silently picks whichever happens to come
            // first in the online-controller list, which was confirmed wrong on a real flight
            // (ranked ESMM_5_CTR when ESMM_7_CTR was the actually-correct one, with both online
            // at once). Now every distinct online match is returned, so callers' tie-detection
            // can decide instead of the resolver silently guessing.
            var chain = new List<string> { "M5", "M7" };
            var online = new List<HandoffController>
            {
                new HandoffController("ESMM_5_CTR", 32765, 57, 14),
                new HandoffController("ESMM_7_CTR", 24155, 56, 16)
            };

            var result = VatGlassesOwnershipResolver.ResolveOnlineControllers(chain, Positions, online);

            Assert.Equal(2, result.Count);
            Assert.Contains(result, c => c.Callsign == "ESMM_5_CTR");
            Assert.Contains(result, c => c.Callsign == "ESMM_7_CTR");
        }

        [Fact]
        public void ResolveOnlineControllers_SameControllerMatchesMultiplePositions_ReturnedOnlyOnce()
        {
            var chain = new List<string> { "M5", "M7" };
            var online = new List<HandoffController> { new HandoffController("ESMM_5_CTR", 32765, 57, 14) };

            var result = VatGlassesOwnershipResolver.ResolveOnlineControllers(chain, Positions, online);

            Assert.Single(result);
        }
    }
}
