using System;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class MinIntervalGateTests
    {
        [Fact]
        public void FirstCallIsAllowed()
        {
            var gate = new MinIntervalGate(TimeSpan.FromMilliseconds(250), () => new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero));
            Assert.True(gate.TryEnter());
        }

        [Fact]
        public void CallInsideIntervalIsRejected_CallAfterIntervalIsAllowed()
        {
            var now = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
            var gate = new MinIntervalGate(TimeSpan.FromMilliseconds(250), () => now);

            Assert.True(gate.TryEnter());
            now = now.AddMilliseconds(100);
            Assert.False(gate.TryEnter());
            now = now.AddMilliseconds(150);
            Assert.True(gate.TryEnter());
        }

        [Fact]
        public void RejectedCallDoesNotExtendTheWindow()
        {
            var now = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
            var gate = new MinIntervalGate(TimeSpan.FromMilliseconds(250), () => now);

            Assert.True(gate.TryEnter());
            now = now.AddMilliseconds(200);
            Assert.False(gate.TryEnter());
            now = now.AddMilliseconds(50);
            Assert.True(gate.TryEnter());
        }
    }
}
