using System.IO;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class PathJoinTests
    {
        [Fact]
        public void Combine_JoinsPlainSegmentsWithOneSeparator()
        {
            Assert.Equal(
                "Handoff" + Path.DirectorySeparatorChar + "vatspy-cache",
                PathJoin.Combine("Handoff", "vatspy-cache"));
        }

        [Fact]
        public void Combine_SkipsNullAndEmptySegments()
        {
            Assert.Equal(
                "a" + Path.DirectorySeparatorChar + "b",
                PathJoin.Combine("a", null, "", "b"));
        }

        [Fact]
        public void Combine_DoesNotInsertSeparatorWhenLeftAlreadyEndsWithOne()
        {
            Assert.Equal(
                "a" + Path.DirectorySeparatorChar + "b",
                PathJoin.Combine("a" + Path.DirectorySeparatorChar, "b"));
        }

        [Fact]
        public void Combine_DoesNotInsertSeparatorWhenRightAlreadyStartsWithOne()
        {
            Assert.Equal(
                "a" + Path.DirectorySeparatorChar + "b",
                PathJoin.Combine("a", Path.DirectorySeparatorChar + "b"));
        }

        [Fact]
        public void Combine_NeverDropsEarlierSegmentsEvenIfALaterOneLooksRooted()
        {
            // This is the exact behavior Path.Combine gets wrong (and CodeQL's cs/path-combine
            // flags): Path.Combine("base", "C:\\rooted") returns just "C:\\rooted", silently
            // discarding "base". PathJoin never does that -- it always keeps every segment.
            var result = PathJoin.Combine("base", @"C:\rooted");
            Assert.Contains("base", result);
            Assert.Contains(@"C:\rooted", result);
            Assert.NotEqual(@"C:\rooted", result);
        }

        [Fact]
        public void Combine_SingleSegment_ReturnsItUnchanged()
        {
            Assert.Equal("only", PathJoin.Combine("only"));
        }

        [Fact]
        public void Combine_AllEmptyOrNull_ReturnsEmptyString()
        {
            Assert.Equal(string.Empty, PathJoin.Combine(null, "", null));
        }
    }
}
