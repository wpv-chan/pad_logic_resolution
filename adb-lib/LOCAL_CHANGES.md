Vendored from https://github.com/MuntashirAkon/libadb-android/tree/3.1.1
under the upstream Apache-2.0 option; upstream notices are retained.

Local changes: 8-second TCP connect timeout and 10-second pairing/TLS handshake
read timeout. Persistent ADB TLS sessions return to an unlimited socket read
timeout after their handshake so that idle sessions remain connected.
Pairing cleanup closes sockets and tolerates partially initialized handshakes.

1.0.1 fixes: stream EOF drains all queued and buffered bytes; local abort wakes
readers; OPEN waits test acknowledged state and cannot lose early notifications;
OPEN has a bounded timeout; connection close closes the actual TLS socket and
uses a bounded thread join. Regression tests cover queued EOF, waiting EOF,
buffered data, early ACK / rejection, local abort and TLS-wrapper cleanup.

Pairing uses the upstream SPAKE2 implementation, not a custom cryptographic protocol.
