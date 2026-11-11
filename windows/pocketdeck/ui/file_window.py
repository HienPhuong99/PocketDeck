"""UltraViewer-style Dual-Pane File Transfer Window + Transfer Queue + Live Chat."""
from __future__ import annotations

import asyncio
import datetime
import os
from pathlib import Path
from typing import Any, Callable, Coroutine

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QAbstractItemView,
    QComboBox,
    QDialog,
    QGroupBox,
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from ..protocol.file_transfer import DirListing, FileSystemProvider, FsEntry, FsRoot, TransferProgress
from .theme import APP_QSS, format_bytes, format_eta, format_speed

AsyncSubmitFn = Callable[[Coroutine[Any, Any, Any]], Any]


class _FsPaneWidget(QGroupBox):
    """Single filesystem browser pane (used for both Local PC and Remote Android/PC)."""

    _roots_loaded = Signal(list)
    _dir_loaded = Signal(object)
    _op_error = Signal(str)
    _op_done = Signal(str)
    files_dropped = Signal(list)

    def __init__(
        self,
        title: str,
        provider: FileSystemProvider,
        submit_coro: AsyncSubmitFn,
        initial_path: str = "",
        accept_external_drop: bool = False,
        parent: QWidget | None = None,
    ):
        super().__init__(title, parent)
        self.provider = provider
        self._submit = submit_coro
        self.current_path: str = initial_path
        self.parent_path: str | None = None
        self._all_entries: list[FsEntry] = []
        self._updating_roots = False

        self.setAcceptDrops(accept_external_drop)
        self._roots_loaded.connect(self._on_roots_loaded)
        self._dir_loaded.connect(self._on_dir_loaded)
        self._op_error.connect(self._show_error)
        self._op_done.connect(lambda _msg: self.refresh())

        layout = QVBoxLayout(self)
        layout.setSpacing(8)

        # Row 1: Root selector + Free space badge
        top_row = QHBoxLayout()
        self.root_combo = QComboBox()
        self.root_combo.currentIndexChanged.connect(self._on_root_selected)
        self.lbl_free = QLabel("")
        self.lbl_free.setObjectName("MutedLabel")
        top_row.addWidget(QLabel("Vị trí nhanh:"))
        top_row.addWidget(self.root_combo, 1)
        top_row.addWidget(self.lbl_free)
        layout.addLayout(top_row)

        # Row 2: Up + Path bar + Refresh
        nav_row = QHBoxLayout()
        self.btn_up = QPushButton("⬆ Lên")
        self.btn_up.clicked.connect(self.go_up)
        self.path_edit = QLineEdit(initial_path)
        self.path_edit.setPlaceholderText("Đường dẫn thư mục...")
        self.path_edit.returnPressed.connect(lambda: self.navigate(self.path_edit.text().strip()))
        self.btn_refresh = QPushButton("Làm mới")
        self.btn_refresh.clicked.connect(self.refresh)
        nav_row.addWidget(self.btn_up)
        nav_row.addWidget(self.path_edit, 1)
        nav_row.addWidget(self.btn_refresh)
        layout.addLayout(nav_row)

        # Row 3: Filter + Mkdir / Rename / Delete
        tools_row = QHBoxLayout()
        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText("Lọc nhanh tên file/thư mục...")
        self.filter_edit.textChanged.connect(self._apply_filter)

        self.btn_mkdir = QPushButton("+ Thư mục")
        self.btn_mkdir.clicked.connect(self._mkdir_prompt)
        self.btn_rename = QPushButton("Đổi tên")
        self.btn_rename.clicked.connect(self._rename_prompt)
        self.btn_delete = QPushButton("Xoá")
        self.btn_delete.clicked.connect(self._delete_prompt)

        tools_row.addWidget(self.filter_edit, 1)
        tools_row.addWidget(self.btn_mkdir)
        tools_row.addWidget(self.btn_rename)
        tools_row.addWidget(self.btn_delete)
        layout.addLayout(tools_row)

        # Row 4: File Table
        self.table = QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels(["Tên", "Kích thước", "Ngày sửa"])
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.setAlternatingRowColors(True)
        self.table.verticalHeader().setVisible(False)
        hdr = self.table.horizontalHeader()
        hdr.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        hdr.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        hdr.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        self.table.cellDoubleClicked.connect(self._on_row_double_clicked)
        layout.addWidget(self.table, 1)

    def load_initial(self) -> None:
        async def _init() -> None:
            try:
                roots = await self.provider.list_roots()
                self._roots_loaded.emit(roots)
                start_path = self.current_path or (roots[0].path if roots else "")
                listing = await self.provider.list_dir(start_path)
                self._dir_loaded.emit(listing)
            except Exception as exc:
                self._op_error.emit(str(exc))

        self._submit(_init())

    def navigate(self, path: str) -> None:
        async def _go() -> None:
            try:
                listing = await self.provider.list_dir(path)
                self._dir_loaded.emit(listing)
            except Exception as exc:
                self._op_error.emit(str(exc))

        self._submit(_go())

    def refresh(self) -> None:
        self.navigate(self.current_path)

    def go_up(self) -> None:
        if self.parent_path is not None:
            self.navigate(self.parent_path)

    def selected_entries(self) -> list[FsEntry]:
        rows = sorted({idx.row() for idx in self.table.selectedIndexes()})
        out: list[FsEntry] = []
        for r in rows:
            item = self.table.item(r, 0)
            if item is not None:
                entry = item.data(Qt.ItemDataRole.UserRole)
                if isinstance(entry, FsEntry):
                    out.append(entry)
        return out

    def _on_roots_loaded(self, roots: list[FsRoot]) -> None:
        self._updating_roots = True
        try:
            self.root_combo.clear()
            for r in roots:
                self.root_combo.addItem(r.name, r.path)
        finally:
            self._updating_roots = False

    def _on_root_selected(self, index: int) -> None:
        if self._updating_roots or index < 0:
            return
        path = self.root_combo.itemData(index)
        if path:
            self.navigate(str(path))

    def _on_dir_loaded(self, listing: DirListing) -> None:
        self.current_path = listing.path
        self.parent_path = listing.parent
        self.path_edit.setText(listing.path)
        self.btn_up.setEnabled(listing.parent is not None)
        if listing.free_bytes > 0:
            self.lbl_free.setText(f"Trống: {format_bytes(listing.free_bytes)}")
        else:
            self.lbl_free.setText("")
        self._all_entries = list(listing.entries)
        self._apply_filter()

    def _apply_filter(self) -> None:
        needle = self.filter_edit.text().strip().lower()
        filtered = [e for e in self._all_entries if not needle or needle in e.name.lower()]
        self.table.setRowCount(len(filtered))
        for row, entry in enumerate(filtered):
            icon = "📁 " if entry.is_dir else "📄 "
            name_item = QTableWidgetItem(f"{icon}{entry.name}")
            name_item.setData(Qt.ItemDataRole.UserRole, entry)

            size_txt = "<Thư mục>" if entry.is_dir else format_bytes(entry.size)
            size_item = QTableWidgetItem(size_txt)
            size_item.setTextAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)

            mtime_txt = (
                datetime.datetime.fromtimestamp(entry.mtime).strftime("%Y-%m-%d %H:%M")
                if entry.mtime > 0
                else "—"
            )
            time_item = QTableWidgetItem(mtime_txt)

            self.table.setItem(row, 0, name_item)
            self.table.setItem(row, 1, size_item)
            self.table.setItem(row, 2, time_item)

    def _on_row_double_clicked(self, row: int, _col: int) -> None:
        item = self.table.item(row, 0)
        if item is None:
            return
        entry = item.data(Qt.ItemDataRole.UserRole)
        if isinstance(entry, FsEntry) and entry.is_dir:
            self.navigate(entry.path)

    def _mkdir_prompt(self) -> None:
        if not self.current_path:
            return
        name, ok = QInputDialog.getText(self, "Tạo thư mục mới", "Tên thư mục mới:")
        if not ok or not name.strip():
            return

        async def _do() -> None:
            try:
                await self.provider.mkdir(self.current_path, name.strip())
                self._op_done.emit("mkdir")
            except Exception as exc:
                self._op_error.emit(str(exc))

        self._submit(_do())

    def _rename_prompt(self) -> None:
        sel = self.selected_entries()
        if len(sel) != 1:
            QMessageBox.information(self, "Đổi tên", "Hãy chọn đúng 1 mục để đổi tên.")
            return
        target = sel[0]
        new_name, ok = QInputDialog.getText(self, "Đổi tên", "Nhập tên mới:", text=target.name)
        if not ok or not new_name.strip() or new_name.strip() == target.name:
            return

        async def _do() -> None:
            try:
                await self.provider.rename(target.path, new_name.strip())
                self._op_done.emit("rename")
            except Exception as exc:
                self._op_error.emit(str(exc))

        self._submit(_do())

    def _delete_prompt(self) -> None:
        sel = self.selected_entries()
        if not sel:
            return
        ans = QMessageBox.question(
            self,
            "Xác nhận xoá",
            f"Bạn có chắc chắn muốn xoá {len(sel)} mục đã chọn?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
        )
        if ans != QMessageBox.StandardButton.Yes:
            return

        async def _do() -> None:
            try:
                for entry in sel:
                    await self.provider.delete(entry.path)
                self._op_done.emit("delete")
            except Exception as exc:
                self._op_error.emit(str(exc))

        self._submit(_do())

    def _show_error(self, message: str) -> None:
        QMessageBox.warning(self, "Lỗi thao tác tệp", message)

    def dragEnterEvent(self, event) -> None:  # noqa: N802
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event) -> None:  # noqa: N802
        paths = [u.toLocalFile() for u in event.mimeData().urls() if u.isLocalFile()]
        if paths:
            self.files_dropped.emit(paths)
            event.acceptProposedAction()


class FileTransferWindow(QDialog):
    """Dual-Pane File Transfer + Queue + Live Chat Window (works identically over DeckLink & ADB)."""

    progress_signal = Signal(object)
    chat_signal = Signal(str, str, bool)  # sender, text, is_self

    def __init__(
        self,
        peer_label: str,
        local_fs: FileSystemProvider,
        remote_fs: FileSystemProvider,
        submit_coro: AsyncSubmitFn,
        send_files_fn: Callable[[list[str], str], Coroutine[Any, Any, Any]],
        pull_files_fn: Callable[[list[str], str], Coroutine[Any, Any, Any]],
        pause_fn: Callable[[str], None] | None = None,
        resume_fn: Callable[[str], None] | None = None,
        cancel_fn: Callable[[str], Coroutine[Any, Any, Any]] | None = None,
        send_chat_fn: Callable[[str], Coroutine[Any, Any, Any]] | None = None,
        initial_local_dir: str = "",
        initial_remote_dir: str = "",
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self.peer_label = peer_label
        self._submit = submit_coro
        self._send_files_fn = send_files_fn
        self._pull_files_fn = pull_files_fn
        self._pause_fn = pause_fn
        self._resume_fn = resume_fn
        self._cancel_fn = cancel_fn
        self._send_chat_fn = send_chat_fn

        self._row_by_xfer: dict[str, int] = {}
        self._xfer_by_id: dict[str, TransferProgress] = {}
        self._refreshed_completed: set[str] = set()

        self.setWindowTitle(f"Truyền File & Chat kiểu UltraViewer — {peer_label}")
        self.resize(1120, 720)
        self.setStyleSheet(APP_QSS)

        self.progress_signal.connect(self._apply_transfer_progress)
        self.chat_signal.connect(self._append_chat_line)

        root = QVBoxLayout(self)
        root.setSpacing(10)

        v_split = QSplitter(Qt.Orientation.Vertical)

        # --- Top Area: Dual File Panes + Center Transfer Buttons ---
        panes_host = QWidget()
        panes_row = QHBoxLayout(panes_host)
        panes_row.setContentsMargins(0, 0, 0, 0)
        panes_row.setSpacing(10)

        self.local_pane = _FsPaneWidget(
            "💻 Máy tính của bạn (Windows)",
            provider=local_fs,
            submit_coro=submit_coro,
            initial_path=initial_local_dir,
            accept_external_drop=False,
        )
        self.remote_pane = _FsPaneWidget(
            f"📱 Thiết bị đối tác ({peer_label})",
            provider=remote_fs,
            submit_coro=submit_coro,
            initial_path=initial_remote_dir,
            accept_external_drop=True,
        )
        self.remote_pane.files_dropped.connect(self.send_local_paths)

        mid_col = QVBoxLayout()
        mid_col.addStretch(1)
        self.btn_send = QPushButton("Gửi sang  →")
        self.btn_send.setObjectName("PrimaryBtn")
        self.btn_send.setMinimumWidth(128)
        self.btn_send.setToolTip("Gửi các file / thư mục đang chọn ở khung bên trái sang thư mục hiện tại của đối tác")
        self.btn_send.clicked.connect(self.transfer_selected_to_remote)

        self.btn_pull = QPushButton("←  Tải về")
        self.btn_pull.setObjectName("PrimaryBtn")
        self.btn_pull.setMinimumWidth(128)
        self.btn_pull.setToolTip("Tải các file / thư mục đang chọn trên thiết bị đối tác về thư mục hiện tại của máy tính")
        self.btn_pull.clicked.connect(self.transfer_selected_to_local)

        lbl_drag_hint = QLabel("Kéo-thả file từ\nExplorer vào khung\nbên phải để gửi")
        lbl_drag_hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lbl_drag_hint.setObjectName("MutedLabel")

        mid_col.addWidget(self.btn_send)
        mid_col.addSpacing(8)
        mid_col.addWidget(self.btn_pull)
        mid_col.addSpacing(12)
        mid_col.addWidget(lbl_drag_hint)
        mid_col.addStretch(1)

        panes_row.addWidget(self.local_pane, 1)
        panes_row.addLayout(mid_col)
        panes_row.addWidget(self.remote_pane, 1)
        v_split.addWidget(panes_host)

        # --- Bottom Area: Transfer Queue (Left) + Live Chat (Right) ---
        bottom_split = QSplitter(Qt.Orientation.Horizontal)

        queue_box = QGroupBox("Hàng đợi Truyền File (256 KB/chunk • Kiểm tra SHA-256 • Tự động Resume)")
        queue_layout = QVBoxLayout(queue_box)

        self.queue_table = QTableWidget(0, 7)
        self.queue_table.setHorizontalHeaderLabels(
            ["Chiều", "Tên file", "Dung lượng", "Tiến độ", "Tốc độ", "Còn lại", "Trạng thái"]
        )
        self.queue_table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.queue_table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.queue_table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.queue_table.setAlternatingRowColors(True)
        self.queue_table.verticalHeader().setVisible(False)
        qhdr = self.queue_table.horizontalHeader()
        qhdr.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        for col in (0, 2, 3, 4, 5, 6):
            qhdr.setSectionResizeMode(col, QHeaderView.ResizeMode.ResizeToContents)
        queue_layout.addWidget(self.queue_table, 1)

        q_btns = QHBoxLayout()
        self.btn_pause_resume = QPushButton("Tạm dừng / Tiếp tục")
        self.btn_pause_resume.setEnabled(pause_fn is not None and resume_fn is not None)
        self.btn_pause_resume.clicked.connect(self._toggle_pause_selected)

        self.btn_cancel_xfer = QPushButton("Huỷ luồng chọn")
        self.btn_cancel_xfer.setEnabled(cancel_fn is not None)
        self.btn_cancel_xfer.clicked.connect(self._cancel_selected)

        btn_open_local = QPushButton("Mở thư mục máy tính")
        btn_open_local.clicked.connect(self._open_local_folder)

        btn_clear_done = QPushButton("Xoá mục hoàn tất")
        btn_clear_done.clicked.connect(self._clear_completed_rows)

        q_btns.addWidget(self.btn_pause_resume)
        q_btns.addWidget(self.btn_cancel_xfer)
        q_btns.addWidget(btn_open_local)
        q_btns.addStretch(1)
        q_btns.addWidget(btn_clear_done)
        queue_layout.addLayout(q_btns)

        chat_box = QGroupBox("Trò chuyện nhanh (Chat)")
        chat_layout = QVBoxLayout(chat_box)
        self.chat_log = QTextEdit()
        self.chat_log.setReadOnly(True)
        self.chat_input = QLineEdit()
        self.chat_input.setPlaceholderText(
            "Nhập tin nhắn gửi sang đối tác..." if send_chat_fn else "Chat trực tiếp khả dụng khi kết nối bằng DeckLink"
        )
        self.chat_input.setEnabled(send_chat_fn is not None)
        self.chat_input.returnPressed.connect(self.send_chat_message)
        self.btn_send_chat = QPushButton("Gửi")
        self.btn_send_chat.setObjectName("PrimaryBtn")
        self.btn_send_chat.setEnabled(send_chat_fn is not None)
        self.btn_send_chat.clicked.connect(self.send_chat_message)

        chat_row = QHBoxLayout()
        chat_row.addWidget(self.chat_input, 1)
        chat_row.addWidget(self.btn_send_chat)
        chat_layout.addWidget(self.chat_log, 1)
        chat_layout.addLayout(chat_row)

        bottom_split.addWidget(queue_box)
        bottom_split.addWidget(chat_box)
        bottom_split.setStretchFactor(0, 7)
        bottom_split.setStretchFactor(1, 4)

        v_split.addWidget(bottom_split)
        v_split.setStretchFactor(0, 6)
        v_split.setStretchFactor(1, 4)
        root.addWidget(v_split, 1)

        self.local_pane.load_initial()
        self.remote_pane.load_initial()

    # ------------------------------------------------------------------ Transfer Actions
    def send_local_paths(self, local_paths: list[str]) -> None:
        if not local_paths:
            return
        dest_dir = self.remote_pane.current_path
        self._submit(self._send_files_fn(local_paths, dest_dir))

    def transfer_selected_to_remote(self) -> None:
        sel = self.local_pane.selected_entries()
        if not sel:
            QMessageBox.information(self, "Chưa chọn tệp", "Hãy chọn ít nhất 1 file hoặc thư mục ở khung bên trái để gửi.")
            return
        self.send_local_paths([e.path for e in sel])

    def transfer_selected_to_local(self) -> None:
        sel = self.remote_pane.selected_entries()
        if not sel:
            QMessageBox.information(self, "Chưa chọn tệp", "Hãy chọn ít nhất 1 file hoặc thư mục trên thiết bị đối tác để tải về.")
            return
        dest_dir = self.local_pane.current_path or str(Path.home() / "Downloads")
        self._submit(self._pull_files_fn([e.path for e in sel], dest_dir))

    def on_transfer_progress_threadsafe(self, prog: TransferProgress) -> None:
        self.progress_signal.emit(prog)

    def _apply_transfer_progress(self, prog: TransferProgress) -> None:
        self._xfer_by_id[prog.xfer_id] = prog
        row = self._row_by_xfer.get(prog.xfer_id)
        if row is None:
            row = self.queue_table.rowCount()
            self.queue_table.insertRow(row)
            self._row_by_xfer[prog.xfer_id] = row
            for col in range(7):
                it = QTableWidgetItem("")
                if col == 0:
                    it.setData(Qt.ItemDataRole.UserRole, prog.xfer_id)
                self.queue_table.setItem(row, col, it)

        dir_txt = "→ Gửi" if prog.direction == "send" else "← Nhận"
        size_txt = f"{format_bytes(prog.transferred_bytes)} / {format_bytes(prog.total_bytes)}"
        pct_txt = f"{prog.percent:5.1f}%"
        spd_txt = format_speed(prog.speed_bps) if prog.status == "active" else "—"
        eta_txt = format_eta(prog.eta_seconds) if prog.status == "active" else "—"

        status_map = {
            "queued": "Đang chờ",
            "active": "Đang truyền" + (" (Resume)" if prog.resumed_from > 0 else ""),
            "paused": "Tạm dừng",
            "completed": "Hoàn tất ✓ (SHA-256 OK)",
            "cancelled": "Đã huỷ",
            "error": f"Lỗi: {prog.error}",
        }
        st_txt = status_map.get(prog.status, prog.status)

        values = [dir_txt, prog.rel_path or prog.filename, size_txt, pct_txt, spd_txt, eta_txt, st_txt]
        for col, val in enumerate(values):
            cell = self.queue_table.item(row, col)
            if cell is not None:
                cell.setText(val)

        if prog.status == "completed" and prog.xfer_id not in self._refreshed_completed:
            self._refreshed_completed.add(prog.xfer_id)
            if prog.direction == "send":
                self.remote_pane.refresh()
            else:
                self.local_pane.refresh()

    def _selected_xfer_id(self) -> str | None:
        row = self.queue_table.currentRow()
        if row < 0:
            return None
        cell = self.queue_table.item(row, 0)
        return str(cell.data(Qt.ItemDataRole.UserRole)) if cell is not None else None

    def _toggle_pause_selected(self) -> None:
        xfer_id = self._selected_xfer_id()
        if not xfer_id:
            return
        prog = self._xfer_by_id.get(xfer_id)
        if prog is None:
            return
        if prog.status == "active" and self._pause_fn is not None:
            self._pause_fn(xfer_id)
        elif prog.status == "paused" and self._resume_fn is not None:
            self._resume_fn(xfer_id)

    def _cancel_selected(self) -> None:
        xfer_id = self._selected_xfer_id()
        if xfer_id and self._cancel_fn is not None:
            self._submit(self._cancel_fn(xfer_id))

    def _open_local_folder(self) -> None:
        target = self.local_pane.current_path
        if target and os.path.isdir(target) and hasattr(os, "startfile"):
            os.startfile(target)  # type: ignore[attr-defined]

    def _clear_completed_rows(self) -> None:
        keep = [
            p
            for p in self._xfer_by_id.values()
            if p.status not in ("completed", "cancelled", "error")
        ]
        self.queue_table.setRowCount(0)
        self._row_by_xfer.clear()
        self._xfer_by_id.clear()
        for p in keep:
            self._apply_transfer_progress(p)

    # ------------------------------------------------------------------ Live Chat
    def on_incoming_chat_threadsafe(self, sender: str, text: str) -> None:
        self.chat_signal.emit(sender, text, False)

    def send_chat_message(self) -> None:
        txt = self.chat_input.text().strip()
        if not txt or self._send_chat_fn is None:
            return
        self.chat_input.clear()
        self.chat_signal.emit("Bạn", txt, True)

        async def _send() -> None:
            await self._send_chat_fn(txt)  # type: ignore[misc]

        self._submit(_send())

    def _append_chat_line(self, sender: str, text: str, is_self: bool) -> None:
        stamp = datetime.datetime.now().strftime("%H:%M:%S")
        color = "#60a5fa" if is_self else "#34d399"
        safe_sender = sender.replace("<", "&lt;").replace(">", "&gt;")
        safe_text = text.replace("<", "&lt;").replace(">", "&gt;")
        self.chat_log.append(
            f'<span style="color:#64748b;">[{stamp}]</span> '
            f'<b style="color:{color};">{safe_sender}:</b> {safe_text}'
        )
