package at.sushi.handoff.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagesTest {

    @Test
    fun decodesControllersMessage() {
        val json = """
            {"type":"controllers","controllers":[
              {"callsign":"EGLL_TWR","frequency":23725,"latitude":51.4775,"longitude":-0.4614}
            ]}
        """.trimIndent()

        val message = decodeServerMessage(json) as ControllersMessage
        val controller = message.controllers.single()
        assertEquals("EGLL_TWR", controller.callsign)
        assertEquals(23725, controller.frequency)
        assertEquals(51.4775, controller.latitude)
        assertEquals(-0.4614, controller.longitude)
    }

    @Test
    fun decodesChatMessageWithPrivateAndRadioEntries() {
        val json = """
            {"type":"chat","messages":[
              {"channel":"private","direction":"incoming","peer":"EGLL_TWR","text":"cleared for takeoff","frequencies":null,"timestamp":"2026-07-25T10:15:30Z"},
              {"channel":"radio","direction":"incoming","peer":null,"text":"report final","frequencies":[23725],"timestamp":"2026-07-25T10:16:05Z"}
            ],"selcalAlerts":[
              {"from":"EGLL_TWR","frequencies":[23725],"timestamp":"2026-07-25T10:16:00Z"}
            ]}
        """.trimIndent()

        val message = decodeServerMessage(json) as ChatMessage
        assertEquals(2, message.messages.size)
        assertEquals("EGLL_TWR", message.messages[0].peer)
        assertNull(message.messages[1].peer)
        assertEquals(listOf(23725), message.messages[1].frequencies)
        assertEquals("EGLL_TWR", message.selcalAlerts.single().from)
    }

    @Test
    fun decodesRadioStateMessageWithNullFrequencies() {
        val json = """{"type":"radioState","com1Frequency":null,"com2Frequency":null,"com1StandbyFrequency":null,"com2StandbyFrequency":null,"modeCEnabled":false,"transponderCode":null}"""

        val message = decodeServerMessage(json) as RadioStateMessage
        assertNull(message.com1Frequency)
        assertNull(message.com2Frequency)
        assertNull(message.com1StandbyFrequency)
        assertNull(message.com2StandbyFrequency)
        assertEquals(false, message.modeCEnabled)
        assertNull(message.transponderCode)
    }

    @Test
    fun decodesRadioStateMessageWithValues() {
        val json = """{"type":"radioState","com1Frequency":23725,"com2Frequency":18000,"com1StandbyFrequency":21000,"com2StandbyFrequency":19000,"modeCEnabled":true,"transponderCode":1200}"""

        val message = decodeServerMessage(json) as RadioStateMessage
        assertEquals(23725, message.com1Frequency)
        assertEquals(18000, message.com2Frequency)
        assertEquals(21000, message.com1StandbyFrequency)
        assertEquals(19000, message.com2StandbyFrequency)
        assertEquals(true, message.modeCEnabled)
        assertEquals(1200, message.transponderCode)
    }

    @Test
    fun returnsNullForUnrecognizedType() {
        assertNull(decodeServerMessage("""{"type":"somethingElse"}"""))
    }

    @Test
    fun encodesSendPrivateMessageCommand() {
        val json = SendPrivateMessageCommand(to = "EGLL_TWR", message = "wilco").encode()
        assertEquals("""{"type":"sendPrivateMessage","to":"EGLL_TWR","message":"wilco"}""", json)
    }

    @Test
    fun encodesSendRadioMessageCommand() {
        val json = SendRadioMessageCommand(message = "request pushback").encode()
        assertEquals("""{"type":"sendRadioMessage","message":"request pushback"}""", json)
    }

    @Test
    fun encodesSetCom1FrequencyCommand() {
        val json = SetCom1FrequencyCommand(megahertz = 123.725).encode()
        assertEquals("""{"type":"setCom1Frequency","megahertz":123.725}""", json)
    }

    @Test
    fun encodesSetCom2FrequencyCommand() {
        val json = SetCom2FrequencyCommand(megahertz = 118.3).encode()
        assertEquals("""{"type":"setCom2Frequency","megahertz":118.3}""", json)
    }

    @Test
    fun encodesSetCom1StandbyFrequencyCommand() {
        val json = SetCom1StandbyFrequencyCommand(megahertz = 121.9).encode()
        assertEquals("""{"type":"setCom1StandbyFrequency","megahertz":121.9}""", json)
    }

    @Test
    fun encodesSetCom2StandbyFrequencyCommand() {
        val json = SetCom2StandbyFrequencyCommand(megahertz = 121.9).encode()
        assertEquals("""{"type":"setCom2StandbyFrequency","megahertz":121.9}""", json)
    }

    @Test
    fun encodesSetTransponderCodeCommand() {
        val json = SetTransponderCodeCommand(transponderCode = 1200).encode()
        assertEquals("""{"type":"setTransponderCode","transponderCode":1200}""", json)
    }

    @Test
    fun formatsCompressedFrequency() {
        assertEquals("123.725", RadioFrequency.format(23725))
        assertEquals("118.000", RadioFrequency.format(18000))
    }
}
