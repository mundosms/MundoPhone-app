# MundoSMS patches on linphone-sdk

They apply on top of the `linphone-sdk` monorepo at tag 5.5.38 (`dbff0da8f`), from its root:
`git apply third_party/linphone-sdk/parches/000N-*.patch`. The SDK is built with
`third_party/linphone-sdk/build-sdk-android.sh`. Every patch writes a log line ending in «(MundoSMS patch)», so its presence
can be checked in the app logs.

## 0001 — belle-sip: 64-bit refresher timer

- **Bug:** `schedule_timer()` computes `obtained_expires * weight` as an `int`, which overflows above ~2,386,092 s (27.6
  days). With 31 days (2,678,400 s), the delay comes out negative (`scheduling next timer in -1884407296 ms`), and a
  negative timer is never armed (`belle_sip_loop.c`).
- **Effect:** the app never refreshes the REGISTER on its own; only when the connection drops or the network changes.
- **Fix:** the delay is an `int64_t` (`belle_sip_timeout_source_new` already accepts it) and the log uses `%lld`.
- **Check:** after the 200 OK of the REGISTER, the log shows
  `scheduling next timer in 2410560000 ms for purpose [normal refresh]` (2,678,400 × 900 with the default weight) and no
  negative value.

## 0002 — belle-sip: `Content-Length: 0` in requests without a body

- **Bug:** the refresher removes the body, `Content-Type` and `Content-Length` from the following requests (`refresher.c`,
  RFC 5367), and `check_content_length()` (`channel.c`) adds nothing when there is no body. The PUBLISH that removes the
  presence (`Expires: 0`) goes out without `Content-Length`.
- **Effect:** over TCP, RFC 3261 §20.14 requires it. Some servers reject the request or keep waiting for the body.
- **Fix:** if there is no `Content-Length` and the message has no body handler, `Content-Length: 0` is added when
  sending. A body of unknown size keeps its handler and is not touched.
- **Check:** the PUBLISH with `Expires: 0` sent by the app carries `Content-Length: 0`.

## 0003 — msaaudio: the player restarts with backoff and recovers on device changes

- **Bug:** on some Samsung devices, switching to the speaker makes `AAudioStream_requestStart()` fail with
  `AAUDIO_ERROR_DISCONNECTED` while Android changes the communication device. The player retries at once (it fails
  again) and gives up. After that, `set_device_id` without a stream only sets `checkForDeviceChange`, which nobody reads
  without a stream: the call stays silent until hang-up.
- **Also:** if a restart fails with `DISCONNECTED`, `_aaudio_player_init` schedules its own retry and
  `aaudio_player_restart` another one, so two run in parallel.
- **Fix:**
  - retries are decided in a single place (`aaudio_player_init` / `aaudio_player_restart`), with increasing waits (100,
    200, 400, 800, 1000 and 1000 ms; up to 7 attempts);
  - if they run out, the next device change tries again;
  - a stream is never opened after postprocess;
  - when the plugin loads, a log line says the patch is there.
- **Check:** `[AAudio] Player restart with backoff … (MundoSMS patch, …)` at startup. In a call, switch to the speaker and
  back several times: the audio must always be heard.

## 0004 — msaaudio: keep the microphone when switching to the speaker

- **Behaviour:** when switching to the speaker, `android_snd_read_change_microphone_according_to_speaker`
  (`msaaudio_recorder.cpp`) changes to the back microphone (`alternative_id`). That sets `device_changed` and the recorder
  is reopened, and `AAudioStreamBuilder_openStream` blocks ~0.5-0.8 s while Android changes the route: the uplink stops
  for that time on every switch to the speaker. The output change is done by the player alone, so the recorder does not
  need to be reopened.
- **Fix:** in speaker mode the current microphone is kept. Going back to the earpiece no longer restarts anything (the
  ID is the same).
- **Check:** `keeping current microphone … (MundoSMS patch)` when switching to the speaker; on a new device model, also
  check that the other party gets no echo in speaker mode.

## 0005 — FCM: the push service waits until the account is registered

- **Bug:** `FirebaseMessaging.onMessageReceived` hands the work to other threads and returns at once. The FCM service
  ends, the process becomes «cached» and Android (Android 14 freezer, Samsung's Freecess) can freeze it with the TCP
  connection just opened and the REGISTER not sent. The SDK wakelocks do not prevent it.
- **Effect:** idle connections of 20+ s and pushes without REGISTER: the REGISTER goes out after the server has closed the
  connection and the call is lost (480).
- **Fix:** `onMessageReceived` does not return until an account is registered (or fails) or the call arrives, 8.5 s at
  most (FCM allows up to ~20 s). It also logs how long FCM took to deliver the push and its priority.
- **Check:** `[Push Notification] Service held [N] ms until registration Ok (MundoSMS patch)` after each push.

## 0006 — belle-sip: freshly connected channel and connection already closed

- **Bug 1:** `last_recv_time` was 0 until the first byte arrived. The channel cleanup run on each push closed a freshly
  connected channel as if it had not received anything for ages. With a duplicate push right after the REGISTER the
  connection was lost: another REGISTER, another 401, +0.5 s.
- **Bug 2:** if the process was frozen while connecting, the connect completes with the server's FIN already queued, the
  channel is considered ready and the REGISTER is written to a half-closed socket.
- **Bug 3:** without `TCP_USER_TIMEOUT`, a dead path without RST (NAT gone) is not detected until 60 s without receiving.
- **Fix:** `last_recv_time` from the moment the channel is ready; when the connect completes, if there is something to
  read it is checked with `MSG_PEEK` and a close is treated as a connection failure; 15 s `TCP_USER_TIMEOUT` on TCP
  sockets.
- **Check:** no `has closed [1] channels` right after connecting; and
  `connection already closed by peer when completed (MundoSMS patch)` when case 2 happens, followed by a new connection
  instead of an `io error`.

## 0007 — ringtone: no `getTitle()`, and timings

- **What:** linphone-sdk 5.5 added `mRingtone.getTitle(context)` to a log line, which is one more MediaStore query on the
  Core thread right before ringing. It is removed (the log has the URI) and the three ringtone steps are timed (URI,
  `getRingtone`, `play`).
- **Check:** `Ringtone timings (MundoSMS patch): uri [..] ms, getRingtone [..] ms, play [..] ms`.

## 0008 — belle-sip: after an explicit rejection, the usual delay

- **Context:** the app enables the increasing REGISTER retry (`[sip] register_retry_base_delay_ms=2000`,
  `register_retry_max_delay_ms=60000`): 2, 4, 8… up to 60 s. In linphone-sdk 5.5 that ladder also applies to error
  responses from the server (403, 5xx without Retry-After, authentication that always fails and so on), which used to wait
  60 s ±25 %.
- **Goal:** an explicit rejection must not be retried faster than before. The fast ladder is for transport failures (io
  error, connection lost), where it helps.
- **Fix:** `retry_later_after_rejection()` uses `retry_after` (60 s ±25 %) for rejections; transport errors and local
  failures keep the ladder. A 401 for an expired nonce does not count as a retry (it is resent with credentials without
  touching the counter); only if authentication fails twice in a row does it come here.
- **Check:** after a 403 or a 503, `explicit rejection, retrying in N ms (MundoSMS patch)` with N between 45000 and
  75000.

## 0009 — msaaudio: recorder clock after reopening, xrun counter and AEC per session

Upstream (5.5.38) stops the stream in `aaudio_recorder_quick_close` before closing it (`requestStop`, wait for the state
change, 10 ms). The patch sits between the stop and the close: first the stream is stopped (upstream), then the hardware
echo canceller of the old session is released (this patch) and finally the stream is closed.

- **Bug 1 (the main one):** on a device change (earpiece↔speaker, Bluetooth) the recorder resynchronised the audio engine
  clock BEFORE opening the new stream. The 0.3-0.8 s that `openStream` takes were measured as a skew between the audio
  clock and the wall clock, and the engine stayed that much behind for the rest of the call: a delay that adds up with each
  change. If the stream disconnected by itself, it was not even resynchronised.
- **Bug 2:** the player xrun counter (`prevXRunCount`) did not go back to 0 when the stream was reopened, so the buffer
  adjustment stopped reacting after the first restart (each xrun is a click).
- **Bug 3:** the hardware AEC of some Samsung devices is tied to the AAudio session; when the recorder was reopened, the
  one of the old session was kept («Using already existing … echo canceller object»).
- **Fix:** the resynchronisation is flagged when reopening and done when the new stream delivers its first samples; the
  counter goes back to 0 with each stream; the AEC is released on quick close and created for the new session.
- **Check:** `New stream delivering samples, ticker synchronizer resynced (MundoSMS patch)` after each device change,
  `Hardware echo canceller of the previous session released (MundoSMS patch)`, and `sound/wall clock skew is average=…`
  not growing after 10 earpiece↔speaker switches in a call (it must stay below ~20 ms).

## 0010 — preloaded ringtone, and assets copied only after an update

- **Ringtone:** on Android 12-14 `RingtoneManager.getRingtone()` prepares the `MediaPlayer` and `setAudioAttributes()`
  (inside `playRingtone`) prepares it again, both times on the Core thread right before ringing (~100-200 ms).
  - When the push arrives, the FCM service calls `prepareRingtoneForPush()`: on a background thread the `Ringtone` is
    created, its attributes and loop are set, and it is kept for 60 s.
  - `startRinging` uses it if the URI matches and it has not expired: only `play()` is left. Otherwise, the usual path.
    The Do Not Disturb checks do not change. If no call arrives, it is released by itself.
  - Check: `Ringtone prepared in [N] ms` after the push and `Ringtone ringing started from the prepared one … play [N] ms`.
- **Assets:** grammars, `rootca.pem` and so on were copied on every startup (24-60 ms before the REGISTER). Now only when
  the package update time changes (stamp `.mundosms_assets_stamp`). Check:
  `Assets already copied for this app version, skipping (MundoSMS patch)`.

## 0011 — `[net] dns_user_hosts_file`

- belle-sip has no DNS cache and `push.mundosms.es` is a CNAME with a 300 s TTL: on almost every cold start from a push
  the REGISTER waited ~150 ms for the query. belle-sip could already read a hosts file (and rereads it on every
  resolution), but liblinphone only used it in its tests.
- The patch reads `[net] dns_user_hosts_file`. The app (`MundoPhoneDns`) writes there the IP given by Android's resolver.
- Check: `DNS user hosts file set to [...] (MundoSMS patch)` and the REGISTER going out without a DNS query.

## 0012 — libopus 1.5.2 with Deep PLC and OSCE

- **What:** `external/opus` is no longer Belledonne's submodule (1.3.1, fixed point on Android) but the official 1.5.2
  tarball (`opus-1.5.2.tar.gz`, sha256 `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1`, 475 files).
  The patch changes `cmake/ExternalDependencies.cmake` (floating point on Android, `OPUS_DEEP_PLC`/`OPUS_OSCE` ON, DRED
  OFF, `OPUS_PRESUME_NEON` on Android, hidden visibility) and `msopus.c` (DECODER complexity 10, or 5 on 32-bit ARM with
  1-2 cores; `MS_OPUS_DEC_COMPLEXITY` forces it).
- **Why:** with Opus and in-band FEC, the downlink quality under packet loss improves by +0.15 to +0.5 PESQ (0 %: 3.99 →
  4.15; 10 %: 3.41 → 3.89; 20 % bursts: 2.83 → 3.13). Without raising the decoder complexity nothing changes.
- **Steps** (on the SDK tree): `git rm external/opus`; delete the whole folder (the old `.gitignore` ignores
  `package_version`, and without it the build says «libopus unknown»); unpack the tarball with `--strip-components=1`;
  `git add -f external/opus .gitmodules` (475 files); apply this patch; build in a NEW folder (the cache keeps the
  `OPUS_*` options of 1.3.1).
- **Pitfalls:** with `PRESUME_NEON` alone, without `MAY_HAVE_NEON`, `celt_neon_intr.c` does not compile; without
  `PRESUME_NEON`, armv7 does not link (`DNN_COMPUTE_*_IMPL`: the CMake of 1.5.2 does not build `arm_dnn_map.c`).
- **Cost:** +2.6 MB per ABI (`libmediastreamer2.so` is 10.6 MB on arm64 and 9.5 MB on armv7; almost all of it weights).
- **Check:** `Opus decoder complexity set to 10: success (MundoSMS patch)` in the call log.

## 0013 — the push service is not stopped until the call service is in the foreground

- **Bug:** when the INVITE of a push arrives, `CorePrivate::addCall` (`core-call.cpp`) stopped the push foreground service
  at once, and the call service (`CoreService`) goes to the foreground about 160 ms later (`onFirstCallStarted` → call
  notification). With the app started by the push and the network blocked by Doze («Push has been received but network
  seems unreachable, starting foreground push service»), Android blocks the network again in that gap,
  `updateNetworkReachability` considers it down (`isConnected()` false) and the transports are closed.
- **Effect:** the TCP connection is reset 0.1 s after the 100 Trying: the CANCEL no longer arrives, the phone keeps
  ringing and can answer, over another connection, a call that no longer exists.
- **Fix:** `AndroidPlatformHelper.stopPushService()`, with a call in progress and the call service not yet in the
  foreground, delays the stop until `setServiceRunningAsForeground(true)`, 5 s at most. Without a call (message push), it
  stops at once as before; a new push cancels the pending stop.
- **Check:** `keeping foreground push service until it is (MundoSMS patch)` and then `Core Service took over, stopping
  foreground push service`. Test: phone idle for a long time (or `adb shell dumpsys deviceidle force-idle`), call hung up
  by the caller after 5 s: the app stops ringing.

## Test only — jitter buffer residence trace (`banco-ortp-traza-colchon.patch`)

With `MS_JB_TRACE` in the environment, ortp logs every second the average and maximum time packets spend in the jitter
buffer (delivered − arrived). It is not enabled on Android and is not part of the app build.
