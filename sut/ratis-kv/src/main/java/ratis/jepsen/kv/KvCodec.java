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

import java.util.regex.Pattern;

/**
 * Encoder/decoder for the ratis-kv wire protocol (DESIGN 1.4): UTF-8 token
 * strings carried in Ratis {@code Message}s, keys matching
 * {@code [A-Za-z0-9_-]+}, values Java longs.
 *
 * <p>Requests:
 * <pre>
 *   PUT &lt;k&gt; &lt;v&gt;             write path
 *   CAS &lt;k&gt; &lt;expect&gt; &lt;update&gt;  write path
 *   ADD &lt;k&gt; &lt;delta&gt;         write path (M3; absent key counts as 0,
 *                           replies VAL &lt;new&gt;)
 *   GET &lt;k&gt;                 read path
 * </pre>
 *
 * <p>Replies:
 * <pre>
 *   OK | VAL &lt;v&gt; | ABSENT | MISMATCH &lt;cur&gt; | ERR &lt;reason&gt;
 * </pre>
 *
 * <p>Request decoding is a total function: malformed input decodes to
 * {@link Request.Malformed} (whose reason becomes an {@code ERR} reply)
 * instead of throwing, because request bytes cross a trust boundary — any
 * client can send anything, and the protocol contract is that malformed
 * input replies {@code ERR <reason>}, never a raw exception. Reply decoding
 * is strict ({@link IllegalArgumentException} on malformed input): replies
 * are produced only by our own state machine, so a malformed reply is a
 * program bug that should fail fast.
 *
 * <p>Tokenization is strict: tokens are separated by exactly one space, with
 * no leading or trailing whitespace. Commands are case-sensitive.
 */
public final class KvCodec {

  /** Legal keys (DESIGN 1.4). */
  public static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

  private KvCodec() {
  }

  /** A decoded request. */
  public sealed interface Request {
    /** {@code PUT <key> <value>} — unconditional write. */
    record Put(String key, long value) implements Request {
    }

    /** {@code CAS <key> <expect> <update>} — compare-and-set. */
    record Cas(String key, long expect, long update) implements Request {
    }

    /** {@code ADD <key> <delta>} — increment; absent key counts as 0 (M3). */
    record Add(String key, long delta) implements Request {
    }

    /** {@code GET <key>} — read. */
    record Get(String key) implements Request {
    }

    /** Anything that failed to decode; {@code reason} becomes an ERR reply. */
    record Malformed(String reason) implements Request {
    }
  }

  /** A decoded reply. */
  public sealed interface Reply {
    /** Successful PUT or CAS. */
    record Ok() implements Reply {
    }

    /** Successful GET of an existing key. */
    record Val(long value) implements Reply {
    }

    /** GET of a missing key, or CAS on a missing key. */
    record Absent() implements Reply {
    }

    /** CAS whose expectation did not match; carries the current value. */
    record Mismatch(long current) implements Reply {
    }

    /** Malformed or misrouted request. */
    record Err(String reason) implements Reply {
    }
  }

  /**
   * Decodes a request string. Total: never throws on malformed input,
   * returning {@link Request.Malformed} instead.
   */
  public static Request decodeRequest(String s) {
    if (s == null || s.isEmpty()) {
      return new Request.Malformed("empty request");
    }
    final String[] tokens = s.split(" ", -1);
    for (String token : tokens) {
      if (token.isEmpty()) {
        return new Request.Malformed("blank token (extra, leading or trailing space)");
      }
    }
    final String command = tokens[0];
    switch (command) {
      case "PUT": {
        if (tokens.length != 3) {
          return new Request.Malformed("PUT expects 2 arguments, got " + (tokens.length - 1));
        }
        if (!isKey(tokens[1])) {
          return new Request.Malformed("invalid key: " + tokens[1]);
        }
        final Long value = parseLong(tokens[2]);
        if (value == null) {
          return new Request.Malformed("invalid long value: " + tokens[2]);
        }
        return new Request.Put(tokens[1], value);
      }
      case "CAS": {
        if (tokens.length != 4) {
          return new Request.Malformed("CAS expects 3 arguments, got " + (tokens.length - 1));
        }
        if (!isKey(tokens[1])) {
          return new Request.Malformed("invalid key: " + tokens[1]);
        }
        final Long expect = parseLong(tokens[2]);
        if (expect == null) {
          return new Request.Malformed("invalid long expect: " + tokens[2]);
        }
        final Long update = parseLong(tokens[3]);
        if (update == null) {
          return new Request.Malformed("invalid long update: " + tokens[3]);
        }
        return new Request.Cas(tokens[1], expect, update);
      }
      case "ADD": {
        if (tokens.length != 3) {
          return new Request.Malformed("ADD expects 2 arguments, got " + (tokens.length - 1));
        }
        if (!isKey(tokens[1])) {
          return new Request.Malformed("invalid key: " + tokens[1]);
        }
        final Long delta = parseLong(tokens[2]);
        if (delta == null) {
          return new Request.Malformed("invalid long delta: " + tokens[2]);
        }
        return new Request.Add(tokens[1], delta);
      }
      case "GET": {
        if (tokens.length != 2) {
          return new Request.Malformed("GET expects 1 argument, got " + (tokens.length - 1));
        }
        if (!isKey(tokens[1])) {
          return new Request.Malformed("invalid key: " + tokens[1]);
        }
        return new Request.Get(tokens[1]);
      }
      default:
        return new Request.Malformed("unknown command: " + command);
    }
  }

  /**
   * Encodes a request. {@link Request.Malformed} has no wire form and
   * throws {@link IllegalArgumentException}.
   */
  public static String encodeRequest(Request request) {
    return switch (request) {
      case Request.Put p -> "PUT " + p.key() + " " + p.value();
      case Request.Cas c -> "CAS " + c.key() + " " + c.expect() + " " + c.update();
      case Request.Add a -> "ADD " + a.key() + " " + a.delta();
      case Request.Get g -> "GET " + g.key();
      case Request.Malformed m ->
          throw new IllegalArgumentException("malformed request has no wire form: " + m.reason());
    };
  }

  /** Encodes a reply. */
  public static String encodeReply(Reply reply) {
    return switch (reply) {
      case Reply.Ok ok -> "OK";
      case Reply.Val v -> "VAL " + v.value();
      case Reply.Absent a -> "ABSENT";
      case Reply.Mismatch m -> "MISMATCH " + m.current();
      case Reply.Err e -> "ERR " + e.reason();
    };
  }

  /**
   * Decodes a reply string. Strict: throws {@link IllegalArgumentException}
   * on anything our state machine could not have produced.
   */
  public static Reply decodeReply(String s) {
    if (s == null || s.isEmpty()) {
      throw new IllegalArgumentException("empty reply");
    }
    if (s.equals("OK")) {
      return new Reply.Ok();
    }
    if (s.equals("ABSENT")) {
      return new Reply.Absent();
    }
    if (s.startsWith("VAL ")) {
      return new Reply.Val(parseLongStrict(s.substring(4), s));
    }
    if (s.startsWith("MISMATCH ")) {
      return new Reply.Mismatch(parseLongStrict(s.substring(9), s));
    }
    if (s.startsWith("ERR ") && s.length() > 4) {
      return new Reply.Err(s.substring(4));
    }
    throw new IllegalArgumentException("malformed reply: " + s);
  }

  private static boolean isKey(String s) {
    return KEY_PATTERN.matcher(s).matches();
  }

  private static Long parseLong(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static long parseLongStrict(String s, String whole) {
    final Long value = parseLong(s);
    if (value == null) {
      throw new IllegalArgumentException("malformed reply: " + whole);
    }
    return value;
  }
}
