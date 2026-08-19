package sh.paseo.glance.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class ProtocolTest {
  @Test fun commandIsCompactAndVersioned() {
    val encoded = WatchProtocol.encode(WatchCommand("one", CommandKind.SEND_PROMPT, workspaceId = "workspace", agentId = "agent", text = "Ship it"))
    val json = JSONObject(encoded.decodeToString())
    assertEquals(1, json.getInt("v"))
    assertEquals("SEND_PROMPT", json.getString("kind"))
    assertEquals("Ship it", json.getString("text"))
  }

  @Test fun responseDecodesCompactSnapshot() {
    val response = WatchProtocol.decodeResponse("""{"v":1,"id":"one","ok":true,"snapshot":{"v":1,"generation":2,"connection":"paired_phone_available","projects":[{"projectId":"p","label":"Project"}],"sessions":[{"workspaceId":"w","projectId":"p","agentId":"a","label":"Session","state":"working","shortStatus":"Building","updatedAt":3,"pinnedAt":4,"latestReply":"Done","activeTurnStartedAt":10,"model":"gpt-5.6-sol","effort":"medium","currentActivity":{"id":"tool","kind":"tool","icon":"shell","label":"Shell","detail":"npm test","state":"running","timestamp":8,"sequence":3}}],"conversation":{"workspaceId":"w","agentId":"a","hasOlder":true,"currentStatus":"Running tests","summary":"**Done**","messages":[{"id":"e:1:1","role":"user","text":"Question","timestamp":5,"sequence":1},{"id":"e:2:2","role":"assistant","text":"Answer","timestamp":6,"sequence":2}],"activities":[{"id":"tool","kind":"tool","icon":"shell","label":"Shell","detail":"npm test","state":"completed","timestamp":8,"sequence":3}]}}}""".encodeToByteArray())
    assertEquals(WatchConnection.PAIRED_PHONE_AVAILABLE, response?.snapshot?.connection)
    assertEquals("Project", response?.snapshot?.projects?.single()?.label)
    assertEquals(WatchState.WORKING, response?.snapshot?.sessions?.single()?.state)
    assertEquals("Done", response?.snapshot?.sessions?.single()?.latestReply)
    assertEquals(4L, response?.snapshot?.sessions?.single()?.pinnedAt)
    assertEquals("gpt-5.6-sol", response?.snapshot?.sessions?.single()?.model)
    assertEquals(WatchActivityState.RUNNING, response?.snapshot?.sessions?.single()?.currentActivity?.state)
    assertEquals(listOf(WatchMessageRole.USER, WatchMessageRole.ASSISTANT), response?.snapshot?.conversation?.messages?.map { it.role })
    assertEquals(true, response?.snapshot?.conversation?.hasOlder)
    assertEquals("Running tests", response?.snapshot?.conversation?.currentStatus)
    assertEquals("**Done**", response?.snapshot?.conversation?.summary)
    assertEquals("Shell", response?.snapshot?.conversation?.activities?.single()?.label)
  }

  @Test fun rejectsFutureProtocolVersion() {
    assertNull(WatchProtocol.decodeResponse("""{"v":2,"id":"one","ok":true}""".encodeToByteArray()))
  }
}
