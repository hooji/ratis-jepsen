/*
 * Copyright 2026 the ratis-jepsen authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ratis.jepsen.kv;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ratis.jepsen.kv.KvCodec.Reply;
import ratis.jepsen.kv.KvCodec.Request;

/** Unit tests for the wire-protocol codec; no cluster involved. */
class KvCodecTest {

  // ---- round-trips: every request form ----

  @Test
  void putRoundTrip() {
    final Request request = new Request.Put("some_key-1", 42L);
    final String wire = KvCodec.encodeRequest(request);
    assertEquals("PUT some_key-1 42", wire);
    assertEquals(request, KvCodec.decodeRequest(wire));
  }

  @Test
  void casRoundTrip() {
    final Request request = new Request.Cas("k", Long.MIN_VALUE, Long.MAX_VALUE);
    final String wire = KvCodec.encodeRequest(request);
    assertEquals("CAS k " + Long.MIN_VALUE + " " + Long.MAX_VALUE, wire);
    assertEquals(request, KvCodec.decodeRequest(wire));
  }

  @Test
  void getRoundTrip() {
    final Request request = new Request.Get("K_9-z");
    final String wire = KvCodec.encodeRequest(request);
    assertEquals("GET K_9-z", wire);
    assertEquals(request, KvCodec.decodeRequest(wire));
  }

  @Test
  void negativeValuesRoundTrip() {
    final Request request = new Request.Put("k", -7L);
    assertEquals(request, KvCodec.decodeRequest(KvCodec.encodeRequest(request)));
  }

  // ---- round-trips: every reply form ----

  @Test
  void replyRoundTrips() {
    final List<Reply> replies = List.of(
        new Reply.Ok(),
        new Reply.Val(17L),
        new Reply.Val(Long.MIN_VALUE),
        new Reply.Absent(),
        new Reply.Mismatch(-3L),
        new Reply.Err("a reason with several words"));
    for (Reply reply : replies) {
      final String wire = KvCodec.encodeReply(reply);
      assertEquals(reply, KvCodec.decodeReply(wire), "round-trip of " + wire);
    }
  }

  @Test
  void replyWireForms() {
    assertEquals("OK", KvCodec.encodeReply(new Reply.Ok()));
    assertEquals("VAL 5", KvCodec.encodeReply(new Reply.Val(5L)));
    assertEquals("ABSENT", KvCodec.encodeReply(new Reply.Absent()));
    assertEquals("MISMATCH 2", KvCodec.encodeReply(new Reply.Mismatch(2L)));
    assertEquals("ERR boom", KvCodec.encodeReply(new Reply.Err("boom")));
  }

  // ---- malformed requests decode to Malformed (never throw), which
  // ---- encode as ERR replies ----

  @ParameterizedTest
  @ValueSource(strings = {
      "PUT k",                       // missing value (brief's list)
      "CAS k 1",                     // missing update (brief's list)
      "",                            // empty (brief's list)
      "PUT ke$y 1",                  // bad key chars (brief's list)
      "PUT k twelve",                // non-long value (brief's list)
      "GET",                         // missing key
      "GET k extra",                 // trailing junk
      "PUT k 1 2",                   // too many arguments
      "CAS k 1 2 3",                 // too many arguments
      "CAS k x 2",                   // non-long expect
      "CAS k 1 x",                   // non-long update
      "put k 1",                     // commands are case-sensitive
      "FROB k",                      // unknown command
      " PUT k 1",                    // leading space
      "PUT k 1 ",                    // trailing space
      "PUT  k 1",                    // double space
      "PUT k 1.5",                   // not a long
      "PUT k 9223372036854775808",   // long overflow
      "GET é",                  // non-ASCII key
  })
  void malformedRequestsDecodeToErrNotExceptions(String wire) {
    final Request decoded = assertDoesNotThrow(() -> KvCodec.decodeRequest(wire));
    final Request.Malformed malformed = assertInstanceOf(Request.Malformed.class, decoded);
    assertFalse(malformed.reason().isBlank(), "reason must explain the problem");
    // and the state machine's reply for it is a legal ERR wire string:
    final String errWire = KvCodec.encodeReply(new Reply.Err(malformed.reason()));
    assertTrue(errWire.startsWith("ERR "), errWire);
    assertEquals(new Reply.Err(malformed.reason()), KvCodec.decodeReply(errWire));
  }

  @Test
  void nullRequestDecodesToMalformed() {
    assertInstanceOf(Request.Malformed.class, KvCodec.decodeRequest(null));
  }

  @Test
  void malformedHasNoWireForm() {
    assertThrows(IllegalArgumentException.class,
        () -> KvCodec.encodeRequest(new Request.Malformed("whatever")));
  }

  // ---- reply decoding is strict: our own state machine is the only
  // ---- producer, so garbage is a bug and must fail fast ----

  @ParameterizedTest
  @ValueSource(strings = {"", "NOPE", "VAL", "VAL x", "MISMATCH ", "ERR ", "OK 1", "val 5"})
  void malformedRepliesThrow(String wire) {
    assertThrows(IllegalArgumentException.class, () -> KvCodec.decodeReply(wire));
  }

  @Test
  void errReplyReasonMayContainSpaces() {
    assertEquals(new Reply.Err("CAS expects 3 arguments, got 1"),
        KvCodec.decodeReply("ERR CAS expects 3 arguments, got 1"));
  }
}
