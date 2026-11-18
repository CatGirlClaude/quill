package dev.catgirlclaude.quill.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.catgirlclaude.quill.client.editor.DatapackInspector;
import dev.catgirlclaude.quill.client.editor.WorkspaceFileCache;
import dev.catgirlclaude.quill.client.session.ClientEditorSession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sidebar inspector panel showing detected scoreboard objectives, command storage namespaces/paths,
 * and macro parameters across all datapack {@code .mcfunction} files with click-to-jump occurrences.
 */
public final class InspectorSidebarWidget {
	private static final int ROW_HEIGHT = 15;

	private enum SectionKind {
		SCOREBOARDS("SCOREBOARD OBJECTIVES", QuillTheme.SYNTAX_IDENTIFIER),
		STORAGE("STORAGE NAMESPACES", QuillTheme.SYNTAX_PROPERTY),
		MACROS("MACRO PARAMETERS", QuillTheme.SYNTAX_MACRO);

		final String title;
		final int symbolColor;

		SectionKind(String title, int symbolColor) {
			this.title = title;
			this.symbolColor = symbolColor;
		}
	}

	private sealed interface RenderRow permits SectionRow, SymbolRow, DetailRow, OccurrenceRow, EmptyRow {
	}

	private record SectionRow(SectionKind kind, int count, IdeLayout.Rect rect) implements RenderRow {
	}

	private record SymbolRow(String expandKey, DatapackInspector.SymbolEntry entry, int color, IdeLayout.Rect rect) implements RenderRow {
	}

	private record DetailRow(String text, IdeLayout.Rect rect) implements RenderRow {
	}

	private record OccurrenceRow(DatapackInspector.Occurrence occurrence, IdeLayout.Rect rect) implements RenderRow {
	}

	private record EmptyRow(String text, IdeLayout.Rect rect) implements RenderRow {
	}

	private final ClientEditorSession session;
	private final WorkspaceFileCache fileCache;

	private DatapackInspector.InspectionReport report = DatapackInspector.InspectionReport.EMPTY;
	private boolean scanning = false;
	private long scanGeneration = 0L;
	private long lastScannedRevision = -1L;

	private final Set<SectionKind> collapsedSections = new HashSet<>();
	private final Set<String> expandedSymbols = new HashSet<>();
	private final List<RenderRow> visibleRows = new ArrayList<>();
	private int scrollPixels = 0;

	private IdeLayout.Rect refreshBtnRect = new IdeLayout.Rect(0, 0, 0, 0);
	private IdeLayout.Rect listRect = new IdeLayout.Rect(0, 0, 0, 0);

	public InspectorSidebarWidget(ClientEditorSession session, WorkspaceFileCache fileCache) {
		this.session = session;
		this.fileCache = fileCache;
	}

	public DatapackInspector.InspectionReport report() {
		return report;
	}

	public void refresh(boolean invalidateCache) {
		if (invalidateCache) {
			fileCache.invalidate();
		}
		long gen = ++scanGeneration;
		long rev = session.workspaceRevision();
		scanning = true;
		lastScannedRevision = rev;
		DatapackInspector.inspectWorkspace(session, fileCache).whenComplete((res, err) -> session.runOnUi(() -> {
			if (gen != scanGeneration) {
				return;
			}
			scanning = false;
			if (err == null && res != null) {
				report = res;
			}
		}));
	}

	public void render(GuiGraphicsExtractor graphics, Font font, IdeLayout.Rect area, int mouseX, int mouseY) {
		if (!scanning && lastScannedRevision != session.workspaceRevision()) {
			refresh(false);
		}

		// Header
		QuillDraw.text(graphics, font, "INSPECTOR", area.x() + 6, area.y() + 5, QuillTheme.TEXT_MUTED);
		String refLabel = "Refresh";
		int refW = font.width(refLabel) + 8;
		refreshBtnRect = new IdeLayout.Rect(area.right() - refW - 5, area.y() + 2, refW, 14);
		boolean hoverRef = refreshBtnRect.contains(mouseX, mouseY);
		if (hoverRef) {
			QuillDraw.fill(graphics, refreshBtnRect.x(), refreshBtnRect.y(), refreshBtnRect.width(), refreshBtnRect.height(), QuillTheme.BG_HOVER);
			QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
		}
		QuillDraw.text(graphics, font, refLabel, refreshBtnRect.x() + 4, area.y() + 5,
				hoverRef ? QuillTheme.TEXT_BRIGHT : QuillTheme.TEXT_MUTED);
		QuillDraw.hLine(graphics, area.x(), area.width() - 1, area.y() + 18, QuillTheme.BORDER_SUBTLE);

		// Summary bar
		String summary = scanning
				? "Scanning .mcfunction files..."
				: "Scanned " + report.scannedFileCount() + " .mcfunction file(s)";
		QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, summary, area.width() - 12),
				area.x() + 6, area.y() + 23, QuillTheme.TEXT_MUTED);
		int listTop = area.y() + 36;
		QuillDraw.hLine(graphics, area.x(), area.width() - 1, listTop - 1, QuillTheme.BORDER_SUBTLE);
		listRect = new IdeLayout.Rect(area.x(), listTop, area.width() - 1, Math.max(0, area.bottom() - listTop));

		renderTree(graphics, font, mouseX, mouseY);
	}

	private void renderTree(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		visibleRows.clear();
		if (listRect.height() <= 0) {
			return;
		}

		int totalRows = countLogicalRows();
		int maxScroll = Math.max(0, totalRows * ROW_HEIGHT - listRect.height() + 4);
		scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels));

		QuillDraw.beginClip(graphics, listRect);
		int[] yRef = {listRect.y() + 2 - scrollPixels};

		renderSection(graphics, font, SectionKind.SCOREBOARDS, report.scoreboardObjectives(), yRef, mouseX, mouseY);
		renderSection(graphics, font, SectionKind.STORAGE, report.storageEntries(), yRef, mouseX, mouseY);
		renderSection(graphics, font, SectionKind.MACROS, report.macroParameters(), yRef, mouseX, mouseY);

		QuillDraw.endClip(graphics);
	}

	private int countLogicalRows() {
		return countSectionRows(SectionKind.SCOREBOARDS, report.scoreboardObjectives())
				+ countSectionRows(SectionKind.STORAGE, report.storageEntries())
				+ countSectionRows(SectionKind.MACROS, report.macroParameters());
	}

	private int countSectionRows(SectionKind kind, List<DatapackInspector.SymbolEntry> entries) {
		int rows = 1; // section header
		if (collapsedSections.contains(kind)) {
			return rows;
		}
		if (entries.isEmpty()) {
			return rows + 1;
		}
		for (DatapackInspector.SymbolEntry entry : entries) {
			rows++;
			String key = expandKey(kind, entry.name());
			if (expandedSymbols.contains(key)) {
				if (!entry.detail().isEmpty()) {
					rows++;
				}
				rows += entry.occurrences().size();
			}
		}
		return rows;
	}

	private void renderSection(
			GuiGraphicsExtractor graphics,
			Font font,
			SectionKind kind,
			List<DatapackInspector.SymbolEntry> entries,
			int[] yRef,
			int mouseX,
			int mouseY
	) {
		int y = yRef[0];
		IdeLayout.Rect secRect = new IdeLayout.Rect(listRect.x(), y, listRect.width(), ROW_HEIGHT);
		boolean collapsed = collapsedSections.contains(kind);
		if (y + ROW_HEIGHT >= listRect.y() && y <= listRect.bottom()) {
			visibleRows.add(new SectionRow(kind, entries.size(), secRect));
			boolean hover = listRect.contains(mouseX, mouseY) && secRect.contains(mouseX, mouseY);
			QuillDraw.fill(graphics, secRect.x(), secRect.y(), secRect.width(), secRect.height(),
					hover ? QuillTheme.BG_HOVER : QuillTheme.BG_PANEL_HEADER);
			if (hover) {
				QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
			}
			String title = (collapsed ? "> " : "v ") + kind.title + " (" + entries.size() + ")";
			int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
			QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, title, secRect.width() - 8),
					secRect.x() + 4, textY, QuillTheme.TEXT_PRIMARY);
		}
		y += ROW_HEIGHT;

		if (!collapsed) {
			if (entries.isEmpty()) {
				IdeLayout.Rect emptyRect = new IdeLayout.Rect(listRect.x(), y, listRect.width(), ROW_HEIGHT);
				if (y + ROW_HEIGHT >= listRect.y() && y <= listRect.bottom()) {
					visibleRows.add(new EmptyRow("(none detected)", emptyRect));
					int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
					QuillDraw.text(graphics, font, "(none detected)", emptyRect.x() + 14, textY, QuillTheme.TEXT_MUTED);
				}
				y += ROW_HEIGHT;
			} else {
				for (DatapackInspector.SymbolEntry entry : entries) {
					String key = expandKey(kind, entry.name());
					boolean expanded = expandedSymbols.contains(key);
					IdeLayout.Rect symRect = new IdeLayout.Rect(listRect.x(), y, listRect.width(), ROW_HEIGHT);
					if (y + ROW_HEIGHT >= listRect.y() && y <= listRect.bottom()) {
						visibleRows.add(new SymbolRow(key, entry, kind.symbolColor, symRect));
						boolean hover = listRect.contains(mouseX, mouseY) && symRect.contains(mouseX, mouseY);
						if (hover) {
							QuillDraw.fill(graphics, symRect.x(), symRect.y(), symRect.width(), symRect.height(), QuillTheme.BG_HOVER);
							QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
						}
						int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
						String chevron = expanded ? "v " : "> ";
						String countBadge = " (" + entry.occurrences().size() + ")";
						int badgeW = font.width(countBadge);
						int maxNameW = Math.max(24, symRect.width() - 14 - badgeW);
						String clippedName = QuillDraw.clipToWidth(font, chevron + entry.name(), maxNameW);
						QuillDraw.text(graphics, font, clippedName, symRect.x() + 8, textY, kind.symbolColor);
						QuillDraw.text(graphics, font, countBadge, symRect.x() + 8 + font.width(clippedName), textY, QuillTheme.TEXT_MUTED);
					}
					y += ROW_HEIGHT;

					if (expanded) {
						if (!entry.detail().isEmpty()) {
							IdeLayout.Rect detRect = new IdeLayout.Rect(listRect.x(), y, listRect.width(), ROW_HEIGHT);
							if (y + ROW_HEIGHT >= listRect.y() && y <= listRect.bottom()) {
								String detLabel = (kind == SectionKind.SCOREBOARDS ? "criteria: " : "paths: ") + entry.detail();
								visibleRows.add(new DetailRow(detLabel, detRect));
								int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
								QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, detLabel, detRect.width() - 22),
										detRect.x() + 18, textY, QuillTheme.TEXT_MUTED);
							}
							y += ROW_HEIGHT;
						}
						for (DatapackInspector.Occurrence occ : entry.occurrences()) {
							IdeLayout.Rect occRect = new IdeLayout.Rect(listRect.x(), y, listRect.width(), ROW_HEIGHT);
							if (y + ROW_HEIGHT >= listRect.y() && y <= listRect.bottom()) {
								visibleRows.add(new OccurrenceRow(occ, occRect));
								boolean hover = listRect.contains(mouseX, mouseY) && occRect.contains(mouseX, mouseY);
								if (hover) {
									QuillDraw.fill(graphics, occRect.x(), occRect.y(), occRect.width(), occRect.height(), QuillTheme.BG_HOVER);
									QuillDraw.requestCursor(graphics, CursorTypes.POINTING_HAND);
								}
								int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
								String base = baseName(occ.relativePath()) + ":" + occ.oneBasedLine();
								String line = base + "  " + occ.context();
								QuillDraw.text(graphics, font, QuillDraw.clipToWidth(font, line, occRect.width() - 22),
										occRect.x() + 18, textY, QuillTheme.TEXT_PRIMARY);
							}
							y += ROW_HEIGHT;
						}
					}
				}
			}
		}

		yRef[0] = y;
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		if (refreshBtnRect.contains(mx, my)) {
			refresh(true);
			return true;
		}
		if (!listRect.contains(mx, my)) {
			return false;
		}
		for (RenderRow row : visibleRows) {
			if (row instanceof SectionRow sr && sr.rect().contains(mx, my)) {
				if (!collapsedSections.add(sr.kind())) {
					collapsedSections.remove(sr.kind());
				}
				return true;
			}
			if (row instanceof SymbolRow sym && sym.rect().contains(mx, my)) {
				if (!expandedSymbols.add(sym.expandKey())) {
					expandedSymbols.remove(sym.expandKey());
				}
				return true;
			}
			if (row instanceof OccurrenceRow occRow && occRow.rect().contains(mx, my)) {
				DatapackInspector.Occurrence occ = occRow.occurrence();
				session.openFileAndSelect(occ.relativePath(), occ.oneBasedLine(), occ.startCol(), occ.endCol());
				return true;
			}
		}
		return false;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!listRect.contains(mouseX, mouseY) || scrollY == 0) {
			return false;
		}
		scrollPixels = Math.max(0, scrollPixels - (int) (scrollY * ROW_HEIGHT * 2));
		return true;
	}

	private static String expandKey(SectionKind kind, String name) {
		return kind.name() + ":" + name;
	}

	private static String baseName(String relativePath) {
		int slash = relativePath.lastIndexOf('/');
		return slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
	}
}
