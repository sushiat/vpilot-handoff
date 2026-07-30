using System.Collections.Generic;
using Xunit;

namespace Handoff.Plugin.Tests
{
    /// <summary>Records ShowCode/CloseWindow calls instead of touching WinForms -- see
    /// IHandoffPairingDisplay's doc comment.</summary>
    public class FakePairingDisplay : IHandoffPairingDisplay
    {
        public List<string> ShownCodes { get; } = new List<string>();
        public int CloseCount { get; private set; }

        public void ShowCode(string code) => ShownCodes.Add(code);
        public void CloseWindow() => CloseCount++;
    }

    public class HandoffPairingSessionTests
    {
        [Fact]
        public void EnsureActiveCode_FirstCall_ShowsANewSixDigitCode()
        {
            var display = new FakePairingDisplay();
            var session = new HandoffPairingSession(display);

            var code = session.EnsureActiveCode();

            Assert.Equal(6, code.Length);
            Assert.True(int.TryParse(code, out _));
            Assert.Single(display.ShownCodes);
            Assert.Equal(code, display.ShownCodes[0]);
        }

        [Fact]
        public void EnsureActiveCode_CalledAgainBeforeExpiry_ReturnsSameCodeButReshowsWindow()
        {
            var display = new FakePairingDisplay();
            var session = new HandoffPairingSession(display);

            var first = session.EnsureActiveCode();
            var second = session.EnsureActiveCode();

            Assert.Equal(first, second);
            // Re-shown every call, not just generated once -- otherwise a pilot manually closing
            // HandoffPairingWindow (its own "X") would desync the display from a code that's
            // still active for matching purposes, leaving it invisible until it happened to
            // expire. ShowCode itself is idempotent/cheap (HandoffPairingWindow only rebuilds the
            // form if it's actually gone), so reshowing on every call is the safe default.
            Assert.Equal(2, display.ShownCodes.Count);
            Assert.Equal(first, display.ShownCodes[1]);
        }

        [Fact]
        public void TryConsumeCode_CorrectCode_SucceedsOnceAndClosesWindow()
        {
            var display = new FakePairingDisplay();
            var session = new HandoffPairingSession(display);
            var code = session.EnsureActiveCode();

            Assert.True(session.TryConsumeCode(code));
            Assert.Equal(1, display.CloseCount);

            // Single-use -- the same code can't be replayed by a second device.
            Assert.False(session.TryConsumeCode(code));
        }

        [Fact]
        public void TryConsumeCode_WrongCode_Fails()
        {
            var display = new FakePairingDisplay();
            var session = new HandoffPairingSession(display);
            session.EnsureActiveCode();

            Assert.False(session.TryConsumeCode("000000"));
        }

        [Fact]
        public void TryConsumeCode_NoActiveCode_Fails()
        {
            var display = new FakePairingDisplay();
            var session = new HandoffPairingSession(display);

            Assert.False(session.TryConsumeCode("123456"));
        }

        [Fact]
        public void TryConsumeCode_TooManyWrongGuesses_InvalidatesCode()
        {
            var display = new FakePairingDisplay();
            var session = new HandoffPairingSession(display);
            var code = session.EnsureActiveCode();

            for (var i = 0; i < 10; i++)
            {
                session.TryConsumeCode("000000");
            }

            // The real code no longer works even though it was never actually guessed --
            // exhausted attempt budget invalidated it outright.
            Assert.False(session.TryConsumeCode(code));
            Assert.Equal(1, display.CloseCount);
        }

        [Fact]
        public void EnsureActiveCode_AfterConsumption_GeneratesAndShowsANewCode()
        {
            var display = new FakePairingDisplay();
            var session = new HandoffPairingSession(display);
            var first = session.EnsureActiveCode();
            session.TryConsumeCode(first);

            var second = session.EnsureActiveCode();

            Assert.Equal(2, display.ShownCodes.Count);
            Assert.Equal(second, display.ShownCodes[1]);
        }
    }
}
