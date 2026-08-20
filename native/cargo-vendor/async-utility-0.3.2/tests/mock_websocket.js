class MockWebSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;

    constructor(url) {
        this.url = String(url);
        this.readyState = MockWebSocket.CONNECTING;
        this.bufferedAmount = 0;
        this.binaryType = "blob";
        this.extensions = "";
        this.protocol = "";
        this.onclose = null;
        this.onerror = null;
        this.onmessage = null;
        this.onopen = null;

        if (!this.url.includes("/pending/")) {
            queueMicrotask(() => {
                if (this.readyState !== MockWebSocket.CONNECTING) {
                    return;
                }

                this.readyState = MockWebSocket.OPEN;
                this.onopen?.call(this, new Event("open"));
            });
        }
    }

    close(code = 1000, reason = "") {
        if (this.readyState >= MockWebSocket.CLOSING) {
            return;
        }

        this.readyState = MockWebSocket.CLOSING;
        queueMicrotask(() => {
            this.readyState = MockWebSocket.CLOSED;
            this.onclose?.call(
                this,
                new CloseEvent("close", {
                    code,
                    reason,
                    wasClean: true,
                }),
            );
        });
    }

    send() {
        if (this.readyState !== MockWebSocket.OPEN) {
            throw new DOMException("WebSocket is not open", "InvalidStateError");
        }
    }
}

export function installMockWebSocket() {
    globalThis.WebSocket = MockWebSocket;
}
