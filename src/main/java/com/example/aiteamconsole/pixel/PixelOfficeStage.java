package com.example.aiteamconsole.pixel;

import com.example.aiteamconsole.AgentProfile;
import com.example.aiteamconsole.AgentRun;
import com.example.aiteamconsole.AgentTask;
import com.example.aiteamconsole.TaskStatus;
import javafx.animation.AnimationTimer;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * "Pixel Office": JavaFX canvas window inspired by pablodelucca/pixel-agents.
 * Each agent profile becomes a character at a desk; live AgentRun status drives a
 * tiny per-character animation. Tasks per agent render as cards above the desk.
 *
 * <p>Procedural placeholder sprites — no external PNGs. We avoid touching the
 * existing main UI; the window is owned by the primary stage and listens to the
 * same observable lists, so it stays in sync without extra wiring.
 */
public final class PixelOfficeStage extends Stage {

    private static final int TILE = 16;
    private static final int SCALE = 3;
    private static final int PX = TILE * SCALE;

    private static final int OFFICE_COLS = 32;
    private static final int OFFICE_ROWS_MIN = 18;

    private static final int DESK_COLS = 4;
    private static final int DESK_ROWS = 4;
    private static final int DESKS_PER_ROW = 4;

    private static final int WALL_HEIGHT_TILES = 9;

    private static final int KANBAN_TOP_PX = 18;
    private static final int KANBAN_SIDE_MARGIN_TILES = 2;
    private static final int KANBAN_HEADER_HEIGHT = 28;
    private static final int KANBAN_CARD_HEIGHT = 38;
    private static final int KANBAN_CARD_GAP = 4;
    private static final int KANBAN_MAX_CARDS_PER_COLUMN = 4;

    private static final long FRAME_MS = 140;

    private final ObservableList<AgentProfile> agents;
    private final ObservableList<AgentTask> tasks;
    private final ObservableList<AgentRun> runs;

    private final Canvas canvas;
    private final GraphicsContext gc;

    private final Map<UUID, CharacterRender> characters = new HashMap<>();
    private int animFrame;
    private long lastFrameAt;
    private AnimationTimer timer;

    public PixelOfficeStage(
            Stage owner,
            ObservableList<AgentProfile> agents,
            ObservableList<AgentTask> tasks,
            ObservableList<AgentRun> runs
    ) {
        this.agents = agents;
        this.tasks = tasks;
        this.runs = runs;

        initOwner(owner);
        setTitle("Pixel Office");

        canvas = new Canvas(OFFICE_COLS * PX, OFFICE_ROWS_MIN * PX);
        gc = canvas.getGraphicsContext2D();
        gc.setImageSmoothing(false);

        StackPane root = new StackPane(canvas);
        root.setStyle("-fx-background-color: #0d0f1a;");
        Scene scene = new Scene(root);
        setScene(scene);

        rebuildScene();
        agents.addListener((ListChangeListener<AgentProfile>) c -> rebuildScene());
        tasks.addListener((ListChangeListener<AgentTask>) c -> render());
        runs.addListener((ListChangeListener<AgentRun>) c -> render());

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                long nowMs = now / 1_000_000L;
                if (nowMs - lastFrameAt >= FRAME_MS) {
                    animFrame = (animFrame + 1) & 0x3FFF;
                    lastFrameAt = nowMs;
                    render();
                }
            }
        };
        timer.start();
        setOnHidden(e -> {
            if (timer != null) timer.stop();
        });
    }

    private void rebuildScene() {
        int agentCount = agents.size();
        int rowsOfDesks = Math.max(1, (agentCount + DESKS_PER_ROW - 1) / DESKS_PER_ROW);
        int neededRows = WALL_HEIGHT_TILES + rowsOfDesks * DESK_ROWS + 2;
        int rows = Math.max(OFFICE_ROWS_MIN, neededRows);
        canvas.setHeight(rows * PX);

        characters.clear();
        for (int i = 0; i < agents.size(); i++) {
            AgentProfile profile = agents.get(i);
            int deskRow = i / DESKS_PER_ROW;
            int deskCol = i % DESKS_PER_ROW;
            int slotCols = OFFICE_COLS / DESKS_PER_ROW;
            int xTile = deskCol * slotCols + (slotCols - DESK_COLS) / 2;
            int yTile = WALL_HEIGHT_TILES + 1 + deskRow * DESK_ROWS;
            characters.put(profile.id(), new CharacterRender(profile, xTile, yTile));
        }

        sizeToScene();
        render();
    }

    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.setFill(Color.web("#1a1f2e"));
        gc.fillRect(0, 0, w, h);

        drawWall(w);
        drawFloor(w, h);
        drawKanbanBoard(w);

        if (agents.isEmpty()) {
            drawEmptyHint(w, h);
            return;
        }

        List<CharacterRender> sorted = new ArrayList<>(characters.values());
        sorted.sort(Comparator.comparingInt((CharacterRender c) -> c.yTile).thenComparingInt(c -> c.xTile));
        for (CharacterRender ch : sorted) {
            drawDesk(ch);
            drawCharacter(ch);
            drawNameTag(ch);
            drawActiveTaskCard(ch);
        }
    }

    private void drawKanbanBoard(double canvasWidth) {
        int boardX = KANBAN_SIDE_MARGIN_TILES * PX;
        int boardY = KANBAN_TOP_PX;
        int boardW = (int) canvasWidth - boardX * 2;
        int boardH = WALL_HEIGHT_TILES * PX - KANBAN_TOP_PX - 18;

        gc.setFill(Color.web("#3a2a1a"));
        gc.fillRect(boardX - 8, boardY - 8, boardW + 16, boardH + 16);
        gc.setFill(Color.web("#5b3a2a"));
        gc.fillRect(boardX - 6, boardY - 6, boardW + 12, 6);
        gc.fillRect(boardX - 6, boardY + boardH, boardW + 12, 6);
        gc.fillRect(boardX - 6, boardY - 6, 6, boardH + 12);
        gc.fillRect(boardX + boardW, boardY - 6, 6, boardH + 12);

        gc.setFill(Color.web("#f4ecd6"));
        gc.fillRect(boardX, boardY, boardW, boardH);

        String[] titles = {"TODO", "DOING", "DONE"};
        Color[] headers = {Color.web("#6ec1ff"), Color.web("#ffd25e"), Color.web("#7be07b")};
        List<List<AgentTask>> columns = bucketTasks();

        int colW = (boardW - 16) / 3;
        for (int i = 0; i < 3; i++) {
            int colX = boardX + 8 + i * colW;
            int colInner = colW - 8;

            gc.setFill(headers[i]);
            gc.fillRect(colX, boardY + 8, colInner, KANBAN_HEADER_HEIGHT);
            gc.setFill(Color.web("#0d0f1a"));
            gc.setFont(Font.font("System", FontWeight.BOLD, 13));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(" " + titles[i] + "  (" + columns.get(i).size() + ")",
                    colX + 4, boardY + 8 + KANBAN_HEADER_HEIGHT - 9);

            int cardY = boardY + 8 + KANBAN_HEADER_HEIGHT + 6;
            List<AgentTask> bucket = columns.get(i);
            int shown = Math.min(KANBAN_MAX_CARDS_PER_COLUMN, bucket.size());
            for (int j = 0; j < shown; j++) {
                AgentTask t = bucket.get(j);
                drawKanbanCard(t, colX, cardY, colInner);
                cardY += KANBAN_CARD_HEIGHT + KANBAN_CARD_GAP;
            }
            int remaining = bucket.size() - shown;
            if (remaining > 0) {
                gc.setFill(Color.web("#5a4030"));
                gc.setFont(Font.font("System", FontWeight.NORMAL, 11));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.fillText("+" + remaining + " more…", colX + 4, cardY + 12);
            }
        }
    }

    private void drawKanbanCard(AgentTask t, int x, int y, int w) {
        Color border = taskBorderColor(t.status());
        gc.setFill(Color.web("#fffdf5"));
        gc.fillRect(x, y, w, KANBAN_CARD_HEIGHT);
        gc.setStroke(border);
        gc.setLineWidth(2);
        gc.strokeRect(x + 1, y + 1, w - 2, KANBAN_CARD_HEIGHT - 2);

        gc.setFill(border);
        gc.fillRect(x, y, 4, KANBAN_CARD_HEIGHT);

        String key = t.taskKey();
        String title = t.title() == null ? "" : t.title();
        String header = key.isBlank() ? title : key;

        gc.setFill(Color.web("#1a1a22"));
        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        gc.setTextAlign(TextAlignment.LEFT);
        int textX = x + 8;
        int maxChars = Math.max(6, (w - 12) / 7);
        gc.fillText(truncate(header, maxChars), textX, y + 14);

        if (!key.isBlank() && !title.isBlank()) {
            gc.setFill(Color.web("#4a4a55"));
            gc.setFont(Font.font("System", FontWeight.NORMAL, 10));
            gc.fillText(truncate(title, maxChars + 4), textX, y + 26);
        }

        String assignee = assigneeName(t.assignedAgentId());
        if (!assignee.isBlank()) {
            gc.setFill(border.darker());
            gc.setFont(Font.font("System", FontWeight.NORMAL, 9));
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(truncate(assignee, 18), x + w - 6, y + KANBAN_CARD_HEIGHT - 5);
        }
    }

    private List<List<AgentTask>> bucketTasks() {
        List<AgentTask> todo = new ArrayList<>();
        List<AgentTask> doing = new ArrayList<>();
        List<AgentTask> done = new ArrayList<>();
        for (AgentTask t : tasks) {
            TaskStatus s = t.status();
            if (s == null) continue;
            switch (s) {
                case DRAFT, QUEUED -> todo.add(t);
                case RUNNING, WAITING_REVIEW -> doing.add(t);
                case DONE, FAILED, CANCELLED -> done.add(t);
            }
        }
        Comparator<AgentTask> byUpdated = Comparator.comparing(AgentTask::updatedAt).reversed();
        todo.sort(byUpdated);
        doing.sort(byUpdated);
        done.sort(byUpdated);
        List<List<AgentTask>> out = new ArrayList<>(3);
        out.add(todo);
        out.add(doing);
        out.add(done);
        return out;
    }

    private String assigneeName(UUID agentId) {
        if (agentId == null) return "";
        for (AgentProfile p : agents) {
            if (Objects.equals(p.id(), agentId)) {
                return p.name() == null ? "" : p.name();
            }
        }
        return "";
    }

    private void drawActiveTaskCard(CharacterRender ch) {
        List<AgentTask> agentTasks = tasksFor(ch.profile.id());
        if (agentTasks.isEmpty()) return;
        AgentTask active = null;
        for (AgentTask t : agentTasks) {
            if (t.status() == TaskStatus.RUNNING || t.status() == TaskStatus.WAITING_REVIEW) {
                active = t;
                break;
            }
        }
        if (active == null) active = agentTasks.get(0);

        int cardW = 96;
        int cardH = 24;
        int x = ch.xTile * PX + DESK_COLS * PX / 2 - cardW / 2;
        int y = ch.yTile * PX - PX - cardH - 6;

        Color border = taskBorderColor(active.status());
        gc.setFill(Color.web("#1b1f33"));
        gc.fillRect(x, y, cardW, cardH);
        gc.setStroke(border);
        gc.setLineWidth(2);
        gc.strokeRect(x + 1, y + 1, cardW - 2, cardH - 2);
        gc.setFill(Color.web("#e8ecff"));
        gc.setFont(Font.font("System", FontWeight.BOLD, 10));
        gc.setTextAlign(TextAlignment.CENTER);
        String key = active.taskKey();
        gc.fillText(truncate(key.isBlank() ? active.title() : key, 14), x + cardW / 2.0, y + 12);
        gc.setFill(border);
        gc.setFont(Font.font("System", FontWeight.BOLD, 9));
        gc.fillText(active.status().name(), x + cardW / 2.0, y + 22);
    }

    private void drawWall(double w) {
        gc.setFill(Color.web("#2a2440"));
        gc.fillRect(0, 0, w, WALL_HEIGHT_TILES * PX);
        gc.setFill(Color.web("#3a335a"));
        for (int x = 0; x < OFFICE_COLS; x++) {
            for (int y = 0; y < WALL_HEIGHT_TILES; y++) {
                if (((x + y) & 1) == 0) {
                    gc.fillRect(x * PX, y * PX, PX, PX);
                }
            }
        }
        gc.setFill(Color.web("#1c1834"));
        gc.fillRect(0, WALL_HEIGHT_TILES * PX - 4, w, 4);
    }

    private void drawFloor(double w, double h) {
        int floorTopY = WALL_HEIGHT_TILES * PX;
        gc.setFill(Color.web("#3a3550"));
        gc.fillRect(0, floorTopY, w, h - floorTopY);
        gc.setFill(Color.web("#2f2b44"));
        int cols = (int) (w / PX);
        int rows = (int) (h / PX);
        for (int x = 0; x < cols; x++) {
            for (int y = WALL_HEIGHT_TILES; y < rows; y++) {
                if (((x + y) & 1) == 0) {
                    gc.fillRect(x * PX, y * PX, PX, PX);
                }
            }
        }
    }

    private void drawEmptyHint(double w, double h) {
        gc.setFill(Color.web("#cfd0ff"));
        gc.setFont(Font.font("System", FontWeight.BOLD, 18));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("Pixel Office is empty. Add agents in the Agents tab.", w / 2.0, h / 2.0);
    }

    private void drawDesk(CharacterRender ch) {
        int x = ch.xTile * PX;
        int y = ch.yTile * PX;
        int deskW = DESK_COLS * PX;
        int deskH = PX;

        gc.setFill(Color.web("#5b3a2a"));
        gc.fillRect(x, y, deskW, deskH);
        gc.setFill(Color.web("#7a4d36"));
        gc.fillRect(x, y, deskW, 4);

        int monX = x + PX / 2;
        int monY = y - PX;
        int monW = PX * 2;
        int monH = PX - 4;
        gc.setFill(Color.web("#1b1b22"));
        gc.fillRect(monX, monY, monW, monH);

        Color screen = monitorScreenColor(ch);
        gc.setFill(screen);
        gc.fillRect(monX + 4, monY + 4, monW - 8, monH - 8);

        if (ch.lastState == CharacterState.TYPING && (animFrame & 1) == 0) {
            gc.setFill(Color.web("#ffffff"));
            for (int i = 0; i < 4; i++) {
                gc.fillRect(monX + 8 + i * 8, monY + 10, 6, 2);
            }
        }
    }

    private void drawCharacter(CharacterRender ch) {
        CharacterState state = stateOf(ch.profile);
        ch.lastState = state;

        int x = ch.xTile * PX + DESK_COLS * PX / 2 - PX / 2;
        int y = ch.yTile * PX + PX;

        int bob = (state == CharacterState.TYPING && (animFrame & 1) == 0) ? -2 : 0;

        Color skin = Color.web("#f1c6a4");
        Color shirt = paletteForName(ch.profile.name());
        Color hair = Color.web("#2a1f17");

        gc.setFill(shirt);
        gc.fillRect(x + 8, y + 18 + bob, PX - 16, PX - 22);
        gc.setFill(skin);
        gc.fillRect(x + 12, y + 6 + bob, PX - 24, 16);
        gc.setFill(hair);
        gc.fillRect(x + 12, y + 2 + bob, PX - 24, 6);

        gc.setFill(Color.web("#101015"));
        gc.fillRect(x + 16, y + 12 + bob, 4, 4);
        gc.fillRect(x + PX - 20, y + 12 + bob, 4, 4);

        drawSpeechBubble(ch, x, y, state);
    }

    private void drawSpeechBubble(CharacterRender ch, int charX, int charY, CharacterState state) {
        String glyph;
        Color bubbleFill;
        Color glyphFill;
        switch (state) {
            case TYPING -> {
                glyph = ".. .";
                bubbleFill = Color.web("#fff7c2");
                glyphFill = Color.web("#222");
            }
            case READING -> {
                glyph = "?";
                bubbleFill = Color.web("#cde0ff");
                glyphFill = Color.web("#1a2c66");
            }
            case WAITING -> {
                glyph = "!";
                bubbleFill = Color.web("#ffd25e");
                glyphFill = Color.web("#5a3a00");
            }
            case DONE -> {
                glyph = "v";
                bubbleFill = Color.web("#b6f5b6");
                glyphFill = Color.web("#0c5a0c");
            }
            case ERROR -> {
                glyph = "x";
                bubbleFill = Color.web("#ffb0b0");
                glyphFill = Color.web("#5a0d0d");
            }
            case IDLE -> {
                return;
            }
            default -> {
                return;
            }
        }

        int bx = charX + PX - 6;
        int by = charY - 10;
        int bw = 30;
        int bh = 22;
        gc.setFill(Color.web("#0d0f1a"));
        gc.fillRect(bx - 2, by - 2, bw + 4, bh + 4);
        gc.setFill(bubbleFill);
        gc.fillRect(bx, by, bw, bh);
        gc.setFill(glyphFill);
        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 14));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(glyph, bx + bw / 2.0, by + bh - 6);
    }

    private void drawNameTag(CharacterRender ch) {
        int x = ch.xTile * PX;
        int y = ch.yTile * PX + DESK_ROWS * PX - PX;

        String name = ch.profile.name() == null ? "(no name)" : ch.profile.name();
        String role = ch.profile.role() == null ? "" : ch.profile.role().label();

        gc.setFill(Color.web("#0d0f1a"));
        gc.fillRect(x, y, DESK_COLS * PX, 30);
        gc.setStroke(Color.web("#5b3a2a"));
        gc.setLineWidth(2);
        gc.strokeRect(x + 1, y + 1, DESK_COLS * PX - 2, 28);

        gc.setFill(Color.web("#f1f3ff"));
        gc.setFont(Font.font("System", FontWeight.BOLD, 12));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(truncate(name, 24), x + DESK_COLS * PX / 2.0, y + 14);

        gc.setFill(Color.web("#9aa2c7"));
        gc.setFont(Font.font("System", FontWeight.NORMAL, 10));
        gc.fillText(truncate(role, 28), x + DESK_COLS * PX / 2.0, y + 26);
    }

    private static Color taskBorderColor(TaskStatus status) {
        if (status == null) return Color.web("#666");
        return switch (status) {
            case RUNNING -> Color.web("#ffd25e");
            case DONE -> Color.web("#7be07b");
            case FAILED -> Color.web("#ff8585");
            case CANCELLED -> Color.web("#9aa2c7");
            case WAITING_REVIEW -> Color.web("#c2a6ff");
            case QUEUED -> Color.web("#6ec1ff");
            case DRAFT -> Color.web("#666");
        };
    }

    private Color monitorScreenColor(CharacterRender ch) {
        return switch (ch.lastState == null ? CharacterState.IDLE : ch.lastState) {
            case TYPING -> Color.web("#ffd25e");
            case READING -> Color.web("#7eb6ff");
            case DONE -> Color.web("#7be07b");
            case ERROR -> Color.web("#ff6363");
            case WAITING -> Color.web("#c2a6ff");
            case IDLE -> Color.web("#2a3050");
        };
    }

    private CharacterState stateOf(AgentProfile profile) {
        Optional<AgentRun> active = runs.stream()
                .filter(r -> Objects.equals(r.agentProfileId(), profile.id()))
                .max(Comparator.comparing(AgentRun::startedAt));
        if (active.isEmpty()) return CharacterState.IDLE;
        AgentRun r = active.get();
        switch (r.status()) {
            case CREATING:
            case RUNNING:
                String step = r.resultSummary() == null ? "" : r.resultSummary().toLowerCase();
                if (step.contains("read") || step.contains("search") || step.contains("grep")) {
                    return CharacterState.READING;
                }
                return CharacterState.TYPING;
            case FINISHED:
                Optional<AgentTask> task = tasks.stream()
                        .filter(t -> Objects.equals(t.id(), r.taskId()))
                        .findFirst();
                if (task.isPresent() && task.get().status() == TaskStatus.WAITING_REVIEW) {
                    return CharacterState.WAITING;
                }
                return CharacterState.DONE;
            case ERROR:
                return CharacterState.ERROR;
            case CANCELLED:
            case UNKNOWN:
            default:
                return CharacterState.IDLE;
        }
    }

    private List<AgentTask> tasksFor(UUID agentId) {
        List<AgentTask> out = new ArrayList<>();
        for (AgentTask t : tasks) {
            if (Objects.equals(t.assignedAgentId(), agentId)) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparing(AgentTask::updatedAt).reversed());
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Color paletteForName(String name) {
        if (name == null || name.isBlank()) return Color.web("#7a83c4");
        int h = Math.abs(name.hashCode());
        Color[] shirts = new Color[] {
                Color.web("#e36b6b"), Color.web("#6ea8ff"), Color.web("#7be07b"),
                Color.web("#c2a6ff"), Color.web("#ffd25e"), Color.web("#5fd0c5"),
                Color.web("#f08fb8"), Color.web("#b0a07a")
        };
        return shirts[h % shirts.length];
    }

    private enum CharacterState {
        IDLE, TYPING, READING, WAITING, DONE, ERROR
    }

    private static final class CharacterRender {
        final AgentProfile profile;
        final int xTile;
        final int yTile;
        CharacterState lastState = CharacterState.IDLE;

        CharacterRender(AgentProfile profile, int xTile, int yTile) {
            this.profile = profile;
            this.xTile = xTile;
            this.yTile = yTile;
        }
    }
}
