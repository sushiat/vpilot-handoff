using Xunit;

namespace Handoff.Plugin.Tests
{
    public class OperationProgressModelTests
    {
        [Fact]
        public void Report_RaisesChangedWithNotFinished()
        {
            var model = new OperationProgressModel();
            OperationProgressEventArgs raised = null;
            model.Changed += (s, e) => raised = e;

            model.Report("vatGlassesSync", "Updating VatGlasses file 1/24");

            Assert.NotNull(raised);
            Assert.Equal("vatGlassesSync", raised.OperationId);
            Assert.Equal("Updating VatGlasses file 1/24", raised.Status);
            Assert.False(raised.Finished);
        }

        [Fact]
        public void Report_TracksOperationInActiveOperations()
        {
            var model = new OperationProgressModel();

            model.Report("vatGlassesSync", "Updating VatGlasses file 1/24");

            Assert.Equal("Updating VatGlasses file 1/24", model.ActiveOperations["vatGlassesSync"]);
        }

        [Fact]
        public void Finish_RemovesFromActiveOperations()
        {
            var model = new OperationProgressModel();
            model.Report("vatGlassesSync", "Updating VatGlasses file 1/24");

            model.Finish("vatGlassesSync");

            Assert.False(model.ActiveOperations.ContainsKey("vatGlassesSync"));
        }

        [Fact]
        public void Finish_WithoutExplicitStatus_EchoesLastReportedStatus()
        {
            var model = new OperationProgressModel();
            model.Report("vatGlassesSync", "Updating VatGlasses file 24/24");
            OperationProgressEventArgs raised = null;
            model.Changed += (s, e) => raised = e;

            model.Finish("vatGlassesSync");

            Assert.Equal("Updating VatGlasses file 24/24", raised.Status);
            Assert.True(raised.Finished);
        }

        [Fact]
        public void Finish_WithExplicitStatus_OverridesLastReportedStatus()
        {
            var model = new OperationProgressModel();
            OperationProgressEventArgs raised = null;
            model.Changed += (s, e) => raised = e;

            // Finish called without any prior Report -- the common "nothing changed" fast path.
            model.Finish("vatGlassesSync", "VatGlasses data up to date");

            Assert.Equal("VatGlasses data up to date", raised.Status);
            Assert.True(raised.Finished);
        }

        [Fact]
        public void Finish_DefaultsToSuccess()
        {
            var model = new OperationProgressModel();
            OperationProgressEventArgs raised = null;
            model.Changed += (s, e) => raised = e;

            model.Finish("vatGlassesSync", "VatGlasses data updated");

            Assert.True(raised.Success);
        }

        [Fact]
        public void Finish_WithSuccessFalse_RaisesChangedWithSuccessFalse()
        {
            var model = new OperationProgressModel();
            OperationProgressEventArgs raised = null;
            model.Changed += (s, e) => raised = e;

            model.Finish("vatGlassesSync", "VatGlasses sync incomplete", success: false);

            Assert.False(raised.Success);
        }
    }
}
