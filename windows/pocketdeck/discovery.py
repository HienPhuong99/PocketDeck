"""LAN discovery and Partner ID resolution via mDNS (_pocketdeck._tcp.local.) + UDP broadcast."""
from __future__ import annotations

import json
import logging
import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Callable

from .config import format_partner_id, normalize_partner_target

log = logging.getLogger(__name__)

SERVICE_TYPE = "_pocketdeck._tcp.local."
UDP_DISCOVERY_PORT = 7201


@dataclass
class DiscoveredPeer:
    device_id: str
    name: str
    platform: str
    host: str
    port: int
    last_seen: float = field(default_factory=time.monotonic)

    @property
    def formatted_id(self) -> str:
        return format_partner_id(self.device_id)


def local_ipv4_addresses() -> list[str]:
    addrs: set[str] = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith(("127.", "169.254.")):
                addrs.add(ip)
    except OSError:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))
            ip = s.getsockname()[0]
            if ip and not ip.startswith(("127.", "169.254.")):
                addrs.add(ip)
    except OSError:
        pass
    return sorted(addrs)


def _broadcast_targets() -> list[str]:
    targets = {"255.255.255.255"}
    for ip in local_ipv4_addresses():
        parts = ip.split(".")
        if len(parts) == 4:
            targets.add(f"{parts[0]}.{parts[1]}.{parts[2]}.255")
    return sorted(targets)


def parse_host_port(target: str, default_port: int = 7200) -> tuple[str, int] | None:
    """If target looks like IP or host:port (not a 9-digit Partner ID), return (host, port)."""
    norm = normalize_partner_target(target)
    if not norm:
        return None
    if len(norm) == 9 and norm.isdigit():
        return None
    if norm.startswith("[") and "]" in norm:
        end = norm.index("]")
        host = norm[1:end]
        rest = norm[end + 1 :]
        port = int(rest[1:]) if rest.startswith(":") and rest[1:].isdigit() else default_port
        return host, port
    if ":" in norm:
        host, port_s = norm.rsplit(":", 1)
        if port_s.isdigit():
            return host.strip(), int(port_s)
    return norm, default_port


class LanDiscovery:
    """Combined UDP broadcast + mDNS peer advertiser and resolver."""

    def __init__(
        self,
        device_id: str,
        device_name: str,
        tcp_port: int,
        platform: str = "windows",
        udp_port: int = UDP_DISCOVERY_PORT,
        enable_mdns: bool = True,
        on_peers_changed: Callable[[list[DiscoveredPeer]], None] | None = None,
    ):
        self.device_id = normalize_partner_target(device_id)
        self.device_name = device_name
        self.tcp_port = tcp_port
        self.platform = platform
        self.udp_port = udp_port
        self.enable_mdns = enable_mdns
        self.on_peers_changed = on_peers_changed

        self._peers: dict[str, DiscoveredPeer] = {}
        self._lock = threading.Lock()
        self._stop_evt = threading.Event()
        self._udp_sock: socket.socket | None = None
        self._udp_thread: threading.Thread | None = None
        self._zc = None
        self._zc_info = None
        self._zc_browser = None
        self.visible = True

    def start(self) -> None:
        self._stop_evt.clear()
        self._start_udp()
        if self.enable_mdns:
            threading.Thread(target=self._start_mdns, name="pocketdeck-mdns", daemon=True).start()

    def stop(self) -> None:
        self._stop_evt.set()
        if self._udp_sock is not None:
            try:
                self._udp_sock.close()
            except OSError:
                pass
            self._udp_sock = None
        if self._udp_thread is not None:
            self._udp_thread.join(timeout=1.5)
            self._udp_thread = None
        self._stop_mdns()

    def update_info(self, device_name: str | None = None, tcp_port: int | None = None, visible: bool | None = None) -> None:
        if device_name is not None:
            self.device_name = device_name
        if tcp_port is not None:
            self.tcp_port = tcp_port
        if visible is not None:
            self.visible = visible
        if self.visible:
            self.broadcast_announce()

    def peers(self) -> list[DiscoveredPeer]:
        now = time.monotonic()
        with self._lock:
            stale = [k for k, p in self._peers.items() if now - p.last_seen > 45.0]
            for k in stale:
                del self._peers[k]
            return sorted(self._peers.values(), key=lambda p: p.name.lower())

    def _record_peer(self, dev_id: str, name: str, platform: str, host: str, port: int) -> None:
        norm_id = normalize_partner_target(dev_id)
        if not norm_id or norm_id == self.device_id or port <= 0:
            return
        if host.startswith("127.") and self.udp_port == UDP_DISCOVERY_PORT:
            # Allow loopback in local tests when explicitly resolving, ignore self-echo on LAN
            pass
        changed = False
        with self._lock:
            prev = self._peers.get(norm_id)
            if prev is None or prev.host != host or prev.port != port or prev.name != name:
                changed = True
            self._peers[norm_id] = DiscoveredPeer(
                device_id=norm_id,
                name=name or norm_id,
                platform=platform or "unknown",
                host=host,
                port=int(port),
                last_seen=time.monotonic(),
            )
        if changed and self.on_peers_changed is not None:
            try:
                self.on_peers_changed(self.peers())
            except Exception:
                log.debug("on_peers_changed callback error", exc_info=True)

    # ------------------------------------------------------------------ UDP
    def _start_udp(self) -> None:
        if self.udp_port <= 0:
            return
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", self.udp_port))
            sock.settimeout(0.5)
            self._udp_sock = sock
            self._udp_thread = threading.Thread(target=self._udp_loop, name="pocketdeck-udp", daemon=True)
            self._udp_thread.start()
            self.broadcast_announce()
        except OSError as exc:
            log.debug("UDP discovery bind skipped on port %d: %s", self.udp_port, exc)

    def _my_payload(self, op: str) -> bytes:
        return json.dumps(
            {
                "op": op,
                "id": self.device_id,
                "name": self.device_name,
                "platform": self.platform,
                "port": self.tcp_port,
                "ver": 1,
            },
            ensure_ascii=False,
        ).encode("utf-8")

    def broadcast_announce(self) -> None:
        if not self.visible or self.tcp_port <= 0:
            return
        pkt = self._my_payload("here")
        self._send_broadcast(pkt)

    def broadcast_discover(self) -> None:
        pkt = json.dumps({"op": "discover", "from": self.device_id}).encode("utf-8")
        self._send_broadcast(pkt)

    def _send_broadcast(self, pkt: bytes) -> None:
        sock = self._udp_sock
        own = False
        if sock is None:
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                own = True
            except OSError:
                return
        try:
            port = self.udp_port if self.udp_port > 0 else UDP_DISCOVERY_PORT
            for bcast in _broadcast_targets():
                try:
                    sock.sendto(pkt, (bcast, port))
                except OSError:
                    pass
        finally:
            if own:
                sock.close()

    def _udp_loop(self) -> None:
        last_announce = time.monotonic()
        while not self._stop_evt.is_set():
            sock = self._udp_sock
            if sock is None:
                break
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                if self.visible and time.monotonic() - last_announce >= 12.0:
                    self.broadcast_announce()
                    last_announce = time.monotonic()
                continue
            except OSError:
                break

            try:
                msg = json.loads(data.decode("utf-8"))
            except Exception:
                continue
            if not isinstance(msg, dict):
                continue

            op = msg.get("op")
            sender_ip = addr[0]
            if op == "here":
                self._record_peer(
                    str(msg.get("id") or ""),
                    str(msg.get("name") or ""),
                    str(msg.get("platform") or ""),
                    sender_ip,
                    int(msg.get("port") or 0),
                )
            elif op == "discover" and self.visible and self.tcp_port > 0:
                if msg.get("from") != self.device_id:
                    try:
                        sock.sendto(self._my_payload("here"), addr)
                    except OSError:
                        pass
            elif op == "resolve" and self.visible and self.tcp_port > 0:
                wanted = normalize_partner_target(str(msg.get("id") or ""))
                if wanted == self.device_id:
                    try:
                        sock.sendto(self._my_payload("here"), addr)
                    except OSError:
                        pass

    def resolve_partner(self, target: str, default_port: int = 7200, timeout: float = 2.0) -> tuple[str, int]:
        """Resolve either 'host[:port]' or a 9-digit UltraViewer Partner ID to (host, port)."""
        direct = parse_host_port(target, default_port=default_port)
        if direct is not None:
            return direct

        wanted_id = normalize_partner_target(target)
        with self._lock:
            cached = self._peers.get(wanted_id)
            if cached is not None and (time.monotonic() - cached.last_seen) < 60.0:
                return cached.host, cached.port

        # Active UDP query to broadcast + loopback (supports both LAN & local E2E testing)
        query = json.dumps({"op": "resolve", "id": wanted_id}).encode("utf-8")
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as qsock:
                qsock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                qsock.settimeout(0.25)
                port = self.udp_port if self.udp_port > 0 else UDP_DISCOVERY_PORT
                targets = _broadcast_targets() + ["127.0.0.1"]
                deadline = time.monotonic() + max(0.3, timeout)
                while time.monotonic() < deadline:
                    for dst in targets:
                        try:
                            qsock.sendto(query, (dst, port))
                        except OSError:
                            pass
                    try:
                        raw, addr = qsock.recvfrom(2048)
                        reply = json.loads(raw.decode("utf-8"))
                        if reply.get("op") == "here" and normalize_partner_target(str(reply.get("id") or "")) == wanted_id:
                            rport = int(reply.get("port") or default_port)
                            self._record_peer(
                                wanted_id,
                                str(reply.get("name") or wanted_id),
                                str(reply.get("platform") or ""),
                                addr[0],
                                rport,
                            )
                            return addr[0], rport
                    except (socket.timeout, ValueError, OSError):
                        pass
                    with self._lock:
                        cached = self._peers.get(wanted_id)
                        if cached is not None:
                            return cached.host, cached.port
        except OSError as exc:
            log.debug("Active UDP resolve error: %s", exc)

        raise LookupError(f"Không tìm thấy thiết bị có ID {format_partner_id(wanted_id)} trong mạng nội bộ")

    # ------------------------------------------------------------------ mDNS
    def _start_mdns(self) -> None:
        try:
            from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, ServiceStateChange, Zeroconf

            zc = Zeroconf(ip_version=IPVersion.V4Only)
            self._zc = zc
            addrs = local_ipv4_addresses()
            if self.visible and self.tcp_port > 0:
                props = {
                    "id": self.device_id,
                    "name": self.device_name,
                    "platform": self.platform,
                    "ver": "1",
                }
                host_label = socket.gethostname().split(".")[0] or "pocketdeck"
                info = ServiceInfo(
                    SERVICE_TYPE,
                    f"PocketDeck-{self.device_id}.{SERVICE_TYPE}",
                    port=self.tcp_port,
                    properties=props,
                    server=f"{host_label}.local.",
                    parsed_addresses=addrs or None,
                )
                zc.register_service(info, allow_name_change=True)
                self._zc_info = info

            def on_service_state_change(zeroconf: Zeroconf, service_type: str, name: str, state_change: ServiceStateChange) -> None:
                if state_change in (ServiceStateChange.Added, ServiceStateChange.Updated):
                    sinfo = zeroconf.get_service_info(service_type, name)
                    if not sinfo:
                        return
                    parsed = sinfo.parsed_addresses()
                    if not parsed:
                        return
                    props_raw = sinfo.properties or {}
                    dev_id = (props_raw.get(b"id") or b"").decode("utf-8", "replace")
                    dev_name = (props_raw.get(b"name") or b"").decode("utf-8", "replace")
                    plat = (props_raw.get(b"platform") or b"").decode("utf-8", "replace")
                    self._record_peer(dev_id, dev_name, plat, parsed[0], sinfo.port or 0)

            self._zc_browser = ServiceBrowser(zc, SERVICE_TYPE, handlers=[on_service_state_change])
        except Exception as exc:
            log.debug("mDNS discovery unavailable: %s", exc)

    def _stop_mdns(self) -> None:
        zc, info = self._zc, self._zc_info
        self._zc = None
        self._zc_info = None
        self._zc_browser = None
        if zc is not None:
            try:
                if info is not None:
                    zc.unregister_service(info)
            except Exception:
                pass
            try:
                zc.close()
            except Exception:
                pass
