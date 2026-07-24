using RossCarlson.Vatsim.Vpilot.Plugins;

namespace Handoff.Plugin
{
    public class HandoffPlugin : IPlugin
    {
        public string Name => "Handoff";

        private IBroker _broker;
        private ControllerStateModel _controllerState;
        private ChatModel _chatModel;
        private RadioStateModel _radioState;

        public void Initialize(IBroker broker)
        {
            _broker = broker;
            _controllerState = new ControllerStateModel(_broker);
            _chatModel = new ChatModel(_broker);
            _radioState = new RadioStateModel();
            _broker.PostDebugMessage("Handoff plugin loaded.");
        }
    }
}
