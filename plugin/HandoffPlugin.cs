using RossCarlson.Vatsim.Vpilot.Plugins;

namespace Handoff.Plugin
{
    public class HandoffPlugin : IPlugin
    {
        public string Name => "Handoff";

        private IBroker _broker;

        public void Initialize(IBroker broker)
        {
            _broker = broker;
            _broker.PostDebugMessage("Handoff plugin loaded.");
        }
    }
}
