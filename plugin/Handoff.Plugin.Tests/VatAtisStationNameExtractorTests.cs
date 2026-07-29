using System.Collections.Generic;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class VatAtisStationNameExtractorTests
    {
        private static string Extract(params string[] lines) => VatAtisStationNameExtractor.Extract(lines);

        [Fact]
        public void CleanNameOnItsOwn_ReturnedAsIs()
        {
            Assert.Equal("Praha Radar", Extract("Praha Radar", "Check ATIS on 122.160"));
        }

        [Fact]
        public void SpacePaddedDash_SplitsBeforeBoilerplate()
        {
            Assert.Equal("Langen Radar", Extract("Langen Radar - CPDLC [EDGS]"));
        }

        [Fact]
        public void SpacePaddedPipe_SplitsBeforeBoilerplate()
        {
            Assert.Equal("Ruzyne Ground", Extract("Ruzyne Ground | PDC available [LKPR]"));
        }

        [Fact]
        public void BareDashWithNoSpaces_IsPartOfTheNameNotASeparator()
        {
            Assert.Equal("Krasnoyarsk-Control", Extract("Krasnoyarsk-Control | Check my coverage at vatsim-radar.com"));
        }

        [Fact]
        public void DirectLetterJoinedCompound_StillMatchesAsRoleWordSuffix()
        {
            Assert.Equal("Eurocontrol", Extract("Eurocontrol"));
        }

        [Fact]
        public void RoleWordFollowedByQualifier_StillMatches_NotJustLastWord()
        {
            Assert.Equal("Brussels Ground North", Extract("Brussels Ground North"));
        }

        [Fact]
        public void QuotedFirstLine_UnwrapsToQuotedContent()
        {
            Assert.Equal("Muscat Control", Extract("\"Muscat Control\" - CPDLC [MUSN] - Solo"));
        }

        [Fact]
        public void CallsignLabel_IsStripped()
        {
            Assert.Equal("Hamburg Tower", Extract("Callsign HAMBURG TOWER - PDC/DCL Logon EDDH"));
        }

        [Fact]
        public void AllCaps_IsTitleCased()
        {
            Assert.Equal("Luebeck Tower", Extract("LUEBECK TOWER"));
        }

        [Fact]
        public void TrailingPeriod_IsTrimmed()
        {
            Assert.Equal("Porto Tower", Extract("Porto Tower."));
        }

        [Fact]
        public void PeriodDashCombo_SplitsAtThePeriod()
        {
            Assert.Equal("Porto Ground", Extract("Porto Ground.- Squawk mode C on ground"));
        }

        [Fact]
        public void SlashCombinedRoleWords_BothWordsCount()
        {
            Assert.Equal("Seoul Departure/Approach", Extract("Seoul Departure/Approach"));
        }

        [Fact]
        public void JokeLine_TooManyWords_ReturnsNull()
        {
            Assert.Null(Extract("its \"Lindbergh Tower\" NOT \"san diego tower\""));
        }

        [Fact]
        public void RamblingChatText_TooManyWords_ReturnsNull()
        {
            Assert.Null(Extract("Events, Discord, Feed back and more! at"));
        }

        [Fact]
        public void SessionDurationAnnouncement_NotAStationName_ReturnsNull()
        {
            Assert.Null(Extract("Online Until 7/30 03z"));
        }

        [Fact]
        public void NoRoleWordEnding_ReturnsNull()
        {
            Assert.Null(Extract("Welcome to Qatar"));
        }

        [Fact]
        public void MoreThanThreeWords_EvenEndingInRoleWord_ReturnsNull()
        {
            Assert.Null(Extract("New Delhi Approach Control Tower"));
        }

        [Fact]
        public void ThreeWords_LongerRealName_IsAccepted()
        {
            Assert.Equal("New Delhi Approach", Extract("New Delhi Approach"));
        }

        [Fact]
        public void NullOrEmptyTextAtis_ReturnsNull()
        {
            Assert.Null(VatAtisStationNameExtractor.Extract(null));
            Assert.Null(VatAtisStationNameExtractor.Extract(new List<string>()));
        }

        [Fact]
        public void EmptyFirstLine_ReturnsNull()
        {
            Assert.Null(Extract("", "Praha Radar"));
        }
    }
}
