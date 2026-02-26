import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact desktop timer/clock widget with persistent state and human-friendly input parsing.
 */
public class HourglassApp {
    private enum Mode { STOPPED, RUNNING, PAUSED, EXPIRED }
    private enum ViewMode { TIMER, CLOCK }

    private static final Color ROOT_BG = new Color(245, 245, 245);
    private static final Color CARD_BG = new Color(236, 236, 236);
    private static final int WINDOW_W = 227;
    private static final int WINDOW_H = 97;
    private static final int WINDOW_MIN_W = 213;
    private static final int WINDOW_MIN_H = 87;
    private static final int WINDOW_ARC = 18;
    private static final String DEFAULT_CLOCK_ZONE = "America/New_York";
    private static final String DEFAULT_DATE_PATTERN = "EEE, MMM d";
    private static final LinkedHashMap<String, String> CLOCK_ZONES = createClockZones();
    private static final LinkedHashMap<String, String> DATE_FORMATS = createDateFormats();

    private final Path settingsPath = resolveSettingsPath();
    private final Properties settings = new Properties();

    private final JFrame frame = new JFrame("Hourglass");
    private final ProgressFramePanel outerPanel = new ProgressFramePanel(5);
    private final JPanel contentPanel = new JPanel();

    private final JLabel titleLabel = new JLabel("Click to enter title", SwingConstants.CENTER);
    private final JTextField titleField = inlineField();
    private final JLabel mainLabel = new JLabel("59 minutes", SwingConstants.CENTER);
    private final JTextField timeField = inlineField();
    private final JLabel subLabel = new JLabel("", SwingConstants.CENTER);
    private final JButton modeSwitchLink = linkButton("Clock");
    private final FadePanel linksPanel = new FadePanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
    private final Component topGlue = Box.createVerticalGlue();
    private final Component bottomGlue = Box.createVerticalGlue();
    private final Component topGap = Box.createVerticalStrut(3);
    private final Component bottomGap = Box.createVerticalStrut(2);

    private final JButton primaryLink = linkButton("Start");
    private final JButton stopLink = linkButton("Stop");
    private final JButton restartLink = linkButton("Restart");
    private final JButton closeLink = linkButton("Close");

    private final JPopupMenu menu = new JPopupMenu();
    private final JCheckBoxMenuItem alwaysOnTopItem = new JCheckBoxMenuItem("Always on top", true);
    private final JCheckBoxMenuItem fullScreenItem = new JCheckBoxMenuItem("Full screen", false);
    private final JCheckBoxMenuItem loopItem = new JCheckBoxMenuItem("Loop timer", false);
    private final JCheckBoxMenuItem popupItem = new JCheckBoxMenuItem("Pop up when expired", true);
    private final JCheckBoxMenuItem closeOnExpireItem = new JCheckBoxMenuItem("Close when expired", false);
    private final JCheckBoxMenuItem dateOnlyScreenshotItem = new JCheckBoxMenuItem("Date only", false);
    private final ButtonGroup tzButtonGroup = new ButtonGroup();
    private final ButtonGroup dateFmtButtonGroup = new ButtonGroup();

    private final Timer tickTimer = new Timer(16, e -> onTick());
    private final Timer linksFadeTimer = new Timer(16, e -> animateLinksFade());

    private Mode mode = Mode.STOPPED;
    private ViewMode viewMode = ViewMode.TIMER;
    private boolean editingTitle = false;
    private boolean mouseOverWindow = false;
    private float linksTargetAlpha = 1f;
    private float linksStartAlpha = 1f;
    private long linksFadeStartNs = 0L;
    private static final int LINKS_FADE_MS = 280;
    private Point dragOffset = null;

    private String timerTitle = "";
    private String lastInput = "59m";
    private ZoneId clockZone = ZoneId.of(DEFAULT_CLOCK_ZONE);
    private String clockDatePattern = DEFAULT_DATE_PATTERN;
    private boolean clockDateOnly = false;
    private long lastClockEpochSecond = Long.MIN_VALUE;

    private long durationMs = 0;
    private long endAtMs = 0;
    private long pausedLeftMs = 0;
    private long expiredAtMs = 0;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new HourglassApp().start());
    }

    private void start() {
        // Initialize persistent state before creating UI so controls reflect saved preferences.
        loadSettings();
        buildUi();
        bindEvents();
        restoreActiveTimer();
        render();
        tickTimer.start();
        frame.setVisible(true);
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setUndecorated(true);
        frame.setSize(WINDOW_W, WINDOW_H);
        frame.setMinimumSize(new Dimension(WINDOW_MIN_W, WINDOW_MIN_H));
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(alwaysOnTopItem.isSelected());
        frame.setBackground(new Color(0, 0, 0, 0));

        RoundedBackgroundPanel root = new RoundedBackgroundPanel(WINDOW_ARC, ROOT_BG);
        root.setBorder(new EmptyBorder(3, 3, 3, 3));
        frame.setContentPane(root);
        installWindowDrag(root);

        root.add(outerPanel, BorderLayout.CENTER);
        installWindowDrag(outerPanel);

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(CARD_BG);
        outerPanel.add(card, BorderLayout.CENTER);
        installWindowDrag(card);

        contentPanel.setBackground(CARD_BG);
        contentPanel.setBorder(new EmptyBorder(4, 6, 3, 6));
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        card.add(contentPanel, BorderLayout.CENTER);
        installWindowDrag(contentPanel);

        contentPanel.add(topGlue);

        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        titleLabel.setForeground(new Color(130, 130, 130));
        titleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        contentPanel.add(titleLabel);

        titleField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        titleField.setHorizontalAlignment(SwingConstants.CENTER);
        titleField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        titleField.setVisible(false);
        contentPanel.add(titleField);

        contentPanel.add(topGap);

        mainLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainLabel.setForeground(new Color(35, 35, 35));
        mainLabel.setFont(new Font("Segoe UI", Font.PLAIN, 17));
        contentPanel.add(mainLabel);

        timeField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        timeField.setHorizontalAlignment(SwingConstants.CENTER);
        timeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        contentPanel.add(timeField);

        subLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        subLabel.setForeground(new Color(100, 100, 100));
        subLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        subLabel.setVisible(false);
        contentPanel.add(subLabel);

        contentPanel.add(bottomGap);

        linksPanel.setOpaque(false);
        linksPanel.add(primaryLink);
        linksPanel.add(stopLink);
        linksPanel.add(restartLink);
        linksPanel.add(modeSwitchLink);
        linksPanel.add(closeLink);
        contentPanel.add(linksPanel);

        contentPanel.add(bottomGlue);

        buildMenu();
    }

    private JTextField inlineField() {
        JTextField field = new JTextField();
        field.setBackground(CARD_BG);
        field.setBorder(new LineBorder(new Color(110, 165, 240), 1));
        return field;
    }

    private JButton linkButton(String text) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setForeground(new Color(28, 103, 205));
        button.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        return button;
    }

    private void buildMenu() {
        JMenuItem newTimer = new JMenuItem("New timer");
        newTimer.addActionListener(e -> stopTimer());

        alwaysOnTopItem.addActionListener(e -> {
            frame.setAlwaysOnTop(alwaysOnTopItem.isSelected());
            saveSettings();
        });

        fullScreenItem.addActionListener(e -> toggleFullscreen(fullScreenItem.isSelected()));

        loopItem.addActionListener(e -> saveSettings());
        popupItem.addActionListener(e -> saveSettings());
        closeOnExpireItem.addActionListener(e -> saveSettings());
        dateOnlyScreenshotItem.addActionListener(e -> {
            clockDateOnly = dateOnlyScreenshotItem.isSelected();
            lastClockEpochSecond = Long.MIN_VALUE;
            saveSettings();
            if (viewMode == ViewMode.CLOCK) render();
        });

        menu.add(newTimer);
        menu.addSeparator();
        menu.add(alwaysOnTopItem);
        menu.add(fullScreenItem);
        menu.addSeparator();
        menu.add(loopItem);
        menu.add(popupItem);
        menu.add(closeOnExpireItem);
        menu.addSeparator();

        JMenu clockMenu = new JMenu("Clock");
        JMenu dateMenu = new JMenu("Date format");
        // Each date format option stores only a formatter pattern; labels are user-facing examples.
        for (Map.Entry<String, String> entry : DATE_FORMATS.entrySet()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(entry.getKey());
            item.setSelected(entry.getValue().equals(clockDatePattern));
            item.addActionListener(e -> {
                clockDatePattern = entry.getValue();
                lastClockEpochSecond = Long.MIN_VALUE;
                saveSettings();
                if (viewMode == ViewMode.CLOCK) render();
            });
            dateFmtButtonGroup.add(item);
            dateMenu.add(item);
        }

        JMenu zoneMenu = new JMenu("Time zone");
        // Keep insertion order stable so the menu stays predictable between launches.
        for (Map.Entry<String, String> entry : CLOCK_ZONES.entrySet()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(entry.getKey());
            item.setSelected(entry.getValue().equals(clockZone.getId()));
            item.addActionListener(e -> {
                clockZone = ZoneId.of(entry.getValue());
                lastClockEpochSecond = Long.MIN_VALUE;
                saveSettings();
                if (viewMode == ViewMode.CLOCK) render();
            });
            tzButtonGroup.add(item);
            zoneMenu.add(item);
        }

        clockMenu.add(dateOnlyScreenshotItem);
        clockMenu.addSeparator();
        clockMenu.add(dateMenu);
        clockMenu.add(zoneMenu);
        menu.add(clockMenu);
    }

    private void bindEvents() {
        MouseAdapter menuHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { maybeShowMenu(e); }

            @Override
            public void mouseReleased(MouseEvent e) { maybeShowMenu(e); }

            private void maybeShowMenu(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
        frame.addMouseListener(menuHandler);
        outerPanel.addMouseListener(menuHandler);
        contentPanel.addMouseListener(menuHandler);

        titleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titleLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) { beginTitleEdit(); }
        });

        titleField.addActionListener(e -> commitTitleEdit(true));
        titleField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) { commitTitleEdit(true); }
        });

        titleField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    commitTitleEdit(false);
                }
            }
        });

        timeField.addActionListener(e -> startFromInlineInput());

        primaryLink.addActionListener(e -> {
            if (mode == Mode.STOPPED || mode == Mode.EXPIRED) {
                startFromInlineInput();
            } else {
                pauseResume();
            }
        });

        stopLink.addActionListener(e -> stopTimer());
        restartLink.addActionListener(e -> restartTimer());
        closeLink.addActionListener(e -> System.exit(0));
        modeSwitchLink.addActionListener(e -> {
            viewMode = (viewMode == ViewMode.TIMER) ? ViewMode.CLOCK : ViewMode.TIMER;
            saveSettings();
            render();
        });

        KeyStroke pauseKs = KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK);
        KeyStroke stopKs = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(pauseKs, "pause");
        frame.getRootPane().getActionMap().put("pause", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { pauseResume(); }
        });
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stopKs, "stop");
        frame.getRootPane().getActionMap().put("stop", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { stopTimer(); }
        });
    }

    private void installWindowDrag(Component c) {
        // The frame is undecorated, so drag behavior is implemented manually on major containers.
        MouseAdapter dragHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    Point p = e.getLocationOnScreen();
                    dragOffset = new Point(p.x - frame.getX(), p.y - frame.getY());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOffset == null) return;
                Point p = e.getLocationOnScreen();
                frame.setLocation(p.x - dragOffset.x, p.y - dragOffset.y);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragOffset = null;
            }
        };
        c.addMouseListener(dragHandler);
        c.addMouseMotionListener(dragHandler);
    }

    private void beginTitleEdit() {
        if (viewMode != ViewMode.TIMER) return;
        if (editingTitle || mode == Mode.EXPIRED) return;
        editingTitle = true;
        titleField.setText(timerTitle);
        titleLabel.setVisible(false);
        titleField.setVisible(true);
        titleField.requestFocusInWindow();
        titleField.selectAll();
        contentPanel.revalidate();
    }

    private void commitTitleEdit(boolean save) {
        if (!editingTitle) return;
        if (save) {
            timerTitle = titleField.getText().trim();
            saveSettings();
        }
        editingTitle = false;
        titleField.setVisible(false);
        titleLabel.setVisible(true);
        renderTitle();
        contentPanel.revalidate();
    }

    private void startFromInlineInput() {
        String input = timeField.getText().trim();
        ParseResult parsed = parseInput(input);
        if (parsed == null || parsed.ms <= 0) {
            // Keep the app non-blocking: inline validation feedback instead of modal dialogs.
            Toolkit.getDefaultToolkit().beep();
            timeField.setBorder(new LineBorder(new Color(190, 80, 80), 1));
            return;
        }

        timeField.setBorder(new LineBorder(new Color(110, 165, 240), 1));
        mode = Mode.RUNNING;
        durationMs = parsed.ms;
        endAtMs = System.currentTimeMillis() + parsed.ms;
        pausedLeftMs = 0;
        lastInput = input;
        addRecent(input);
        saveSettings();
        render();
    }

    private void pauseResume() {
        if (mode != Mode.RUNNING && mode != Mode.PAUSED) return;

        if (mode == Mode.RUNNING) {
            pausedLeftMs = Math.max(0, endAtMs - System.currentTimeMillis());
            mode = Mode.PAUSED;
        } else {
            endAtMs = System.currentTimeMillis() + pausedLeftMs;
            mode = Mode.RUNNING;
        }

        saveSettings();
        render();
    }

    private void restartTimer() {
        if (durationMs <= 0) return;
        mode = Mode.RUNNING;
        endAtMs = System.currentTimeMillis() + durationMs;
        pausedLeftMs = 0;
        saveSettings();
        render();
    }

    private void stopTimer() {
        mode = Mode.STOPPED;
        durationMs = 0;
        endAtMs = 0;
        pausedLeftMs = 0;
        expiredAtMs = 0;
        saveSettings();
        render();
    }

    private void onTick() {
        // Keep hover-dependent controls responsive in both timer and clock modes.
        updateMouseOverWindow();

        if (viewMode == ViewMode.CLOCK) {
            refreshClock(false);
            return;
        }

        if (mode == Mode.RUNNING) {
            long left = endAtMs - System.currentTimeMillis();
            if (left <= 0) {
                onExpire();
                return;
            }
            // Update only dynamic fields every frame; structural UI changes are handled by render().
            mainLabel.setText(formatWords(left));
            outerPanel.setProgress(progressFor(left));
        } else if (mode == Mode.PAUSED) {
            mainLabel.setText(formatWords(pausedLeftMs));
        } else if (mode == Mode.EXPIRED) {
            long agoSec = Math.max(0, (System.currentTimeMillis() - expiredAtMs) / 1000);
            subLabel.setText(agoSec + " seconds ago");
        }
    }

    private float progressFor(long leftMs) {
        if (durationMs <= 0) return 0f;
        double elapsed = 1.0 - ((double) leftMs / (double) durationMs);
        return (float) Math.max(0.0, Math.min(1.0, elapsed));
    }

    private void onExpire() {
        // Loop mode immediately starts the next cycle and optionally plays a notification tone.
        if (loopItem.isSelected() && durationMs > 0) {
            endAtMs = System.currentTimeMillis() + durationMs;
            mode = Mode.RUNNING;
            if (popupItem.isSelected()) {
                playSound();
            }
            render();
            return;
        }

        mode = Mode.EXPIRED;
        expiredAtMs = System.currentTimeMillis();
        outerPanel.setProgress(1f);
        playSound();

        if (popupItem.isSelected()) {
            Toolkit.getDefaultToolkit().beep();
        }

        if (closeOnExpireItem.isSelected()) {
            saveSettings();
            System.exit(0);
        }

        saveSettings();
        render();
    }

    private void render() {
        // render() is the single source of truth for visibility/state of controls in timer mode.
        modeSwitchLink.setText(viewMode == ViewMode.TIMER ? "Clock" : "Timer");

        if (viewMode == ViewMode.CLOCK) {
            renderClockMode();
            contentPanel.revalidate();
            contentPanel.repaint();
            return;
        }

        renderTitle();
        titleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        titleLabel.setForeground(new Color(130, 130, 130));
        mainLabel.setFont(new Font("Segoe UI", Font.PLAIN, 17));
        mainLabel.setForeground(new Color(35, 35, 35));
        subLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        subLabel.setForeground(new Color(100, 100, 100));
        if (mode == Mode.RUNNING || mode == Mode.PAUSED) {
            mouseOverWindow = isPointerOverWindow();
        }

        if (editingTitle) {
            titleLabel.setVisible(false);
            titleField.setVisible(true);
        } else {
            boolean hidePlaceholder = timerTitle.isBlank()
                    && (mode == Mode.RUNNING || mode == Mode.PAUSED)
                    && !mouseOverWindow;
            titleLabel.setVisible(!hidePlaceholder);
            titleField.setVisible(false);
        }
        topGap.setVisible(titleLabel.isVisible() || titleField.isVisible());

        contentPanel.setBackground(CARD_BG);
        contentPanel.setBorder(new EmptyBorder(4, 6, 3, 6));

        outerPanel.setExpired(mode == Mode.EXPIRED);
        if (mode == Mode.EXPIRED) {
            outerPanel.setProgress(1f);
        } else if (mode == Mode.STOPPED) {
            outerPanel.setProgress(0f);
        }

        mainLabel.setVisible(true);
        timeField.setVisible(false);
        subLabel.setVisible(false);
        primaryLink.setVisible(false);
        stopLink.setVisible(false);
        restartLink.setVisible(false);
        modeSwitchLink.setVisible(false);
        closeLink.setVisible(false);

        switch (mode) {
            case STOPPED:
                mainLabel.setVisible(false);
                timeField.setVisible(true);
                timeField.setText((lastInput == null || lastInput.isBlank()) ? "59m" : lastInput);
                primaryLink.setText("Start");
                primaryLink.setVisible(true);
                modeSwitchLink.setVisible(true);
                closeLink.setText("Close");
                closeLink.setVisible(true);
                setLinksVisibility(true, false);
                break;
            case RUNNING:
                mainLabel.setText(formatWords(Math.max(0, endAtMs - System.currentTimeMillis())));
                primaryLink.setText("Pause");
                closeLink.setText("Close");
                primaryLink.setVisible(true);
                stopLink.setVisible(true);
                restartLink.setVisible(true);
                modeSwitchLink.setVisible(true);
                closeLink.setVisible(true);
                setLinksVisibility(mouseOverWindow, true);
                break;
            case PAUSED:
                mainLabel.setText(formatWords(pausedLeftMs));
                primaryLink.setText("Resume");
                closeLink.setText("Close");
                primaryLink.setVisible(true);
                stopLink.setVisible(true);
                restartLink.setVisible(true);
                modeSwitchLink.setVisible(true);
                closeLink.setVisible(true);
                setLinksVisibility(mouseOverWindow, true);
                break;
            case EXPIRED:
                contentPanel.setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(new Color(241, 235, 226), 1),
                        new EmptyBorder(4, 6, 3, 6)
                ));
                if (timerTitle.isBlank()) {
                    titleLabel.setVisible(false);
                    topGap.setVisible(false);
                }
                mainLabel.setFont(new Font("Segoe UI", Font.PLAIN, 16));
                mainLabel.setText("Timer expired");
                subLabel.setVisible(true);
                long agoSec = Math.max(0, (System.currentTimeMillis() - expiredAtMs) / 1000);
                subLabel.setText(agoSec + " seconds ago");
                restartLink.setVisible(true);
                modeSwitchLink.setVisible(true);
                closeLink.setText("Close");
                closeLink.setVisible(true);
                setLinksVisibility(true, false);
                break;
        }

        bottomGap.setVisible(linksPanel.isVisible());
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    private void renderClockMode() {
        commitTitleEdit(false);
        mouseOverWindow = isPointerOverWindow();

        contentPanel.setBackground(CARD_BG);
        contentPanel.setBorder(new EmptyBorder(4, 6, 3, 6));
        outerPanel.setExpired(false);
        outerPanel.setProgress(0f);

        titleLabel.setVisible(!clockDateOnly);
        titleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 17));
        titleLabel.setForeground(new Color(70, 70, 70));
        titleField.setVisible(false);
        topGap.setVisible(!clockDateOnly);
        mainLabel.setVisible(true);
        mainLabel.setFont(new Font("Segoe UI", clockDateOnly ? Font.BOLD : Font.PLAIN, clockDateOnly ? 22 : 17));
        timeField.setVisible(false);
        subLabel.setVisible(false);

        primaryLink.setVisible(false);
        stopLink.setVisible(false);
        restartLink.setVisible(false);
        modeSwitchLink.setVisible(true);
        closeLink.setText("Close");
        closeLink.setVisible(true);
        setLinksVisibility(mouseOverWindow, true);
        bottomGap.setVisible(linksPanel.isVisible());

        refreshClock(true);
    }

    private void refreshClock(boolean force) {
        ZonedDateTime now = ZonedDateTime.now(clockZone);
        long epochSec = now.toEpochSecond();
        // Avoid repaint churn: clock text changes only once per second.
        if (!force && epochSec == lastClockEpochSecond) return;
        lastClockEpochSecond = epochSec;

        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern(clockDatePattern, Locale.ENGLISH);
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.ENGLISH);
        if (clockDateOnly) {
            titleLabel.setText("");
            mainLabel.setText(now.format(dateFmt));
        } else {
            titleLabel.setText(now.format(dateFmt));
            mainLabel.setText(now.format(timeFmt));
        }
    }

    private void setLinksVisibility(boolean show, boolean animate) {
        // Links fade in/out while the timer runs to reduce visual noise when not hovered.
        float target = show ? 1f : 0f;
        if (Math.abs(target - linksTargetAlpha) < 0.001f && animate && linksFadeTimer.isRunning()) {
            return;
        }
        linksTargetAlpha = target;

        if (show && !linksPanel.isVisible()) {
            linksPanel.setVisible(true);
        }

        if (viewMode == ViewMode.CLOCK || mode == Mode.RUNNING || mode == Mode.PAUSED) {
            primaryLink.setEnabled(show);
            stopLink.setEnabled(show);
            restartLink.setEnabled(show);
            modeSwitchLink.setEnabled(show);
            closeLink.setEnabled(show);
        } else {
            primaryLink.setEnabled(true);
            stopLink.setEnabled(true);
            restartLink.setEnabled(true);
            modeSwitchLink.setEnabled(true);
            closeLink.setEnabled(true);
        }

        if (!animate) {
            linksPanel.setAlpha(linksTargetAlpha);
            linksFadeTimer.stop();
            linksFadeStartNs = 0L;
            if (!show) {
                linksPanel.setVisible(false);
            }
            bottomGap.setVisible(linksPanel.isVisible());
            contentPanel.revalidate();
            contentPanel.repaint();
            return;
        }

        if (Math.abs(linksPanel.getAlpha() - linksTargetAlpha) < 0.01f) {
            linksPanel.setAlpha(linksTargetAlpha);
            linksFadeTimer.stop();
            linksFadeStartNs = 0L;
            if (!show) {
                linksPanel.setVisible(false);
            }
            bottomGap.setVisible(linksPanel.isVisible());
            contentPanel.revalidate();
            contentPanel.repaint();
            return;
        }

        linksStartAlpha = linksPanel.getAlpha();
        linksFadeStartNs = System.nanoTime();
        if (!linksFadeTimer.isRunning()) {
            linksFadeTimer.start();
        }
    }

    private void animateLinksFade() {
        if (linksFadeStartNs == 0L) {
            linksFadeStartNs = System.nanoTime();
            linksStartAlpha = linksPanel.getAlpha();
        }

        long elapsedNs = System.nanoTime() - linksFadeStartNs;
        float t = Math.max(0f, Math.min(1f, elapsedNs / (LINKS_FADE_MS * 1_000_000f)));
        float eased = t * t * (3f - 2f * t); // Smoothstep easing for less abrupt transitions.
        float next = linksStartAlpha + (linksTargetAlpha - linksStartAlpha) * eased;
        linksPanel.setAlpha(next);

        if (t >= 1f) {
            linksPanel.setAlpha(linksTargetAlpha);
            linksFadeTimer.stop();
            linksFadeStartNs = 0L;
            if (linksTargetAlpha <= 0.001f) {
                linksPanel.setVisible(false);
            }
            bottomGap.setVisible(linksPanel.isVisible());
            contentPanel.revalidate();
            contentPanel.repaint();
        }
    }

    private void renderTitle() {
        if (editingTitle) return;
        titleLabel.setText(timerTitle.isBlank() ? "Click to enter title" : timerTitle);
    }

    private void updateMouseOverWindow() {
        boolean next = isPointerOverWindow();
        if (next != mouseOverWindow) {
            mouseOverWindow = next;
            if (viewMode == ViewMode.CLOCK || mode == Mode.RUNNING || mode == Mode.PAUSED) {
                render();
            }
        }
    }

    private boolean isPointerOverWindow() {
        if (!frame.isShowing()) return false;
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo == null) return false;
        Point p = pointerInfo.getLocation();
        return frame.getBounds().contains(p);
    }

    private String formatWords(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;

        if (hours > 0) {
            return hours + " hour" + (hours == 1 ? "" : "s") + " "
                    + minutes + " minute" + (minutes == 1 ? "" : "s");
        }
        if (minutes > 0) {
            if (seconds > 0) {
                return minutes + " minute" + (minutes == 1 ? "" : "s") + " "
                        + seconds + " second" + (seconds == 1 ? "" : "s");
            }
            return minutes + " minute" + (minutes == 1 ? "" : "s");
        }
        return seconds + " second" + (seconds == 1 ? "" : "s");
    }

    private void toggleFullscreen(boolean on) {
        frame.dispose();
        frame.setUndecorated(true);
        frame.setVisible(true);
        frame.setAlwaysOnTop(alwaysOnTopItem.isSelected());
        if (on) {
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        } else {
            frame.setExtendedState(JFrame.NORMAL);
            frame.setSize(WINDOW_W, WINDOW_H);
            frame.setLocationRelativeTo(null);
        }
    }

    private void playSound() {
        List<Integer> sequence = Arrays.asList(950, 950, 950);
        new Thread(() -> {
            for (int freq : sequence) {
                tone(freq, 180);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "hourglass-sound").start();
    }

    private void tone(int hz, int ms) {
        float sampleRate = 44100f;
        byte[] buf = new byte[1];
        AudioFormat format = new AudioFormat(sampleRate, 8, 1, true, false);

        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            int samples = (int) (ms * (sampleRate / 1000f));
            for (int i = 0; i < samples; i++) {
                double angle = i / (sampleRate / hz) * 2.0 * Math.PI;
                buf[0] = (byte) (Math.sin(angle) * 100);
                line.write(buf, 0, 1);
            }
            line.drain();
        } catch (Exception e) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private ParseResult parseInput(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);

        // Prefer relative durations first; if that fails, interpret as an absolute target date/time.
        Long duration = parseDuration(s);
        if (duration != null && duration > 0) return new ParseResult(duration);

        Long absolute = parseAbsoluteMs(s);
        if (absolute != null && absolute > 0) return new ParseResult(absolute);

        return null;
    }

    private Long parseDuration(String s) {
        if (s.matches("^\\d+$")) return Long.parseLong(s) * 60_000L;

        // "mm:ss" or "hh:mm:ss" style input, including '.' as a separator.
        Matcher clock = Pattern.compile("^(\\d+)([:.])(\\d{2})(?:\\2(\\d{2}))?$").matcher(s);
        if (clock.matches()) {
            long a = Long.parseLong(clock.group(1));
            long b = Long.parseLong(clock.group(3));
            String c = clock.group(4);
            if (c == null) return (a * 60 + b) * 1000L;
            return (a * 3600 + b * 60 + Long.parseLong(c)) * 1000L;
        }

        // Token parser handles free-form text such as "1h 20m", "2.5h", "3days".
        Matcher token = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([a-z]+)").matcher(s);
        double total = 0;
        int found = 0;
        int consumed = 0;

        while (token.find()) {
            found++;
            consumed += token.group(0).length();
            double value = Double.parseDouble(token.group(1));
            Long mul = unitToMs(token.group(2));
            if (mul == null) return null;
            total += value * mul;
        }

        if (found > 0 && consumed >= s.replace(" ", "").length() - 1) {
            return (long) total;
        }

        return null;
    }

    private Long unitToMs(String u) {
        switch (u) {
            case "s":
            case "sec":
            case "secs":
            case "second":
            case "seconds":
                return 1000L;
            case "m":
            case "min":
            case "mins":
            case "minute":
            case "minutes":
                return 60_000L;
            case "h":
            case "hr":
            case "hrs":
            case "hour":
            case "hours":
                return 3_600_000L;
            case "d":
            case "day":
            case "days":
                return 86_400_000L;
            case "w":
            case "week":
            case "weeks":
                return 604_800_000L;
            case "mo":
            case "month":
            case "months":
                return 2_629_800_000L;
            case "y":
            case "year":
            case "years":
                return 31_557_600_000L;
            default:
                return null;
        }
    }

    private Long parseAbsoluteMs(String s) {
        LocalDateTime now = LocalDateTime.now();

        if (s.equals("tomorrow")) {
            return Duration.between(now, LocalDate.now().plusDays(1).atStartOfDay()).toMillis();
        }

        Matcher tomorrowAt = Pattern.compile("^tomorrow\\s+(?:at\\s+)?(.+)$").matcher(s);
        if (tomorrowAt.matches()) {
            LocalTime lt = parseTime(tomorrowAt.group(1));
            if (lt != null) {
                return Duration.between(now, LocalDate.now().plusDays(1).atTime(lt)).toMillis();
            }
        }

        // Supports expressions like "fri", "monday next week at 9am", "wed at 14:30".
        Matcher weekday = Pattern.compile("^(sun|mon|tue|wed|thu|fri|sat)(?:day)?(?:\\s+(next week|next|after next week))?(?:\\s+at\\s+(.+))?$").matcher(s);
        if (weekday.matches()) {
            DayOfWeek day = dayFromShort(weekday.group(1));
            LocalDate target = LocalDate.now().with(TemporalAdjusters.next(day));
            String extra = weekday.group(2);
            if (extra != null) {
                if (extra.contains("next week")) target = target.plusWeeks(1);
                if (extra.contains("after next")) target = target.plusWeeks(2);
            }
            LocalTime lt = parseTime(weekday.group(3) == null ? "00:00" : weekday.group(3));
            if (lt == null) lt = LocalTime.MIDNIGHT;
            return Duration.between(now, LocalDateTime.of(target, lt)).toMillis();
        }

        LocalTime timeOnly = parseTime(s);
        if (timeOnly != null) {
            LocalDateTime target = LocalDate.now().atTime(timeOnly);
            if (!target.isAfter(now)) target = target.plusDays(1);
            return Duration.between(now, target).toMillis();
        }

        // Date + time split parser for text like "Feb 26 at 10:15 pm" or "2026-02-26 22:15".
        Matcher dateTime = Pattern.compile("^(.+?)\\s+(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)$").matcher(s);
        if (dateTime.matches()) {
            LocalDate d = parseDate(dateTime.group(1));
            LocalTime t = parseTime(dateTime.group(2));
            if (d != null && t != null) {
                LocalDateTime target = LocalDateTime.of(d, t);
                if (!target.isAfter(now)) target = target.plusYears(1);
                return Duration.between(now, target).toMillis();
            }
        }

        LocalDate d = parseDate(s);
        if (d != null) {
            LocalDateTime target = d.atStartOfDay();
            if (!target.isAfter(now)) target = target.plusYears(1);
            return Duration.between(now, target).toMillis();
        }

        return null;
    }

    private LocalTime parseTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").matcher(s);
        if (!m.matches()) return null;

        int hh = Integer.parseInt(m.group(1));
        int mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        String ap = m.group(3);

        if (ap != null) {
            // Convert 12-hour input to 24-hour clock.
            if (hh == 12) hh = 0;
            if ("pm".equals(ap)) hh += 12;
        }

        if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return null;
        return LocalTime.of(hh, mm);
    }

    private LocalDate parseDate(String raw) {
        String s = raw.trim();

        // Numeric date parser: M/D, M/D/YY, M/D/YYYY.
        Matcher slash = Pattern.compile("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$").matcher(s);
        if (slash.matches()) {
            int month = Integer.parseInt(slash.group(1));
            int day = Integer.parseInt(slash.group(2));
            int year = slash.group(3) == null ? LocalDate.now().getYear() : Integer.parseInt(slash.group(3));
            if (year < 100) year += 2000;
            try {
                return LocalDate.of(year, month, day);
            } catch (DateTimeException e) {
                return null;
            }
        }

        // Named month and ISO alternatives.
        List<String> formats = Arrays.asList("MMM d", "MMMM d", "MMM d yyyy", "MMMM d yyyy", "yyyy-MM-dd");
        for (String f : formats) {
            try {
                DateTimeFormatter dtf = DateTimeFormatter.ofPattern(f, Locale.ENGLISH);
                if (f.contains("yyyy")) {
                    return LocalDate.parse(s, dtf);
                }
                MonthDay md = MonthDay.parse(s, dtf);
                return md.atYear(LocalDate.now().getYear());
            } catch (DateTimeParseException ignored) {
            }
        }

        return null;
    }

    private DayOfWeek dayFromShort(String s) {
        switch (s) {
            case "sun":
                return DayOfWeek.SUNDAY;
            case "mon":
                return DayOfWeek.MONDAY;
            case "tue":
                return DayOfWeek.TUESDAY;
            case "wed":
                return DayOfWeek.WEDNESDAY;
            case "thu":
                return DayOfWeek.THURSDAY;
            case "fri":
                return DayOfWeek.FRIDAY;
            default:
                return DayOfWeek.SATURDAY;
        }
    }

    private void restoreActiveTimer() {
        // Restore only active countdowns; expired/stopped timers should not auto-resume.
        boolean running = Boolean.parseBoolean(settings.getProperty("running", "false"));
        if (!running) return;

        long end = parseLong(settings.getProperty("endAtMs"), 0L);
        long now = System.currentTimeMillis();

        if (end > now) {
            mode = Mode.RUNNING;
            endAtMs = end;
            durationMs = parseLong(settings.getProperty("durationMs"), 0L);
            pausedLeftMs = Math.max(0, endAtMs - now);
        }
    }

    private void loadSettings() {
        // Missing/corrupt settings are tolerated; defaults keep the app usable.
        if (Files.exists(settingsPath)) {
            try (InputStream in = Files.newInputStream(settingsPath)) {
                settings.load(in);
            } catch (IOException ignored) {
            }
        }

        timerTitle = settings.getProperty("title", "");
        lastInput = settings.getProperty("lastInput", "59m");
        viewMode = "clock".equalsIgnoreCase(settings.getProperty("viewMode", "timer"))
                ? ViewMode.CLOCK : ViewMode.TIMER;
        clockDatePattern = settings.getProperty("clockDatePattern", DEFAULT_DATE_PATTERN);
        clockDateOnly = Boolean.parseBoolean(settings.getProperty("clockDateOnly", "false"));
        String zoneId = settings.getProperty("clockZone", DEFAULT_CLOCK_ZONE);
        try {
            clockZone = ZoneId.of(zoneId);
        } catch (Exception ignored) {
            clockZone = ZoneId.of(DEFAULT_CLOCK_ZONE);
        }
        dateOnlyScreenshotItem.setSelected(clockDateOnly);

        alwaysOnTopItem.setSelected(Boolean.parseBoolean(settings.getProperty("alwaysOnTop", "true")));
        loopItem.setSelected(Boolean.parseBoolean(settings.getProperty("loop", "false")));
        popupItem.setSelected(Boolean.parseBoolean(settings.getProperty("popup", "true")));
        closeOnExpireItem.setSelected(Boolean.parseBoolean(settings.getProperty("closeOnExpire", "false")));
    }

    private void saveSettings() {
        settings.setProperty("title", timerTitle == null ? "" : timerTitle);
        settings.setProperty("lastInput", lastInput == null ? "59m" : lastInput);
        settings.setProperty("viewMode", viewMode == ViewMode.CLOCK ? "clock" : "timer");
        settings.setProperty("clockDatePattern", clockDatePattern);
        settings.setProperty("clockDateOnly", Boolean.toString(clockDateOnly));
        settings.setProperty("clockZone", clockZone.getId());
        settings.setProperty("alwaysOnTop", Boolean.toString(alwaysOnTopItem.isSelected()));
        settings.setProperty("loop", Boolean.toString(loopItem.isSelected()));
        settings.setProperty("popup", Boolean.toString(popupItem.isSelected()));
        settings.setProperty("closeOnExpire", Boolean.toString(closeOnExpireItem.isSelected()));

        // Persist enough runtime state to recover active timers after restart.
        boolean running = (mode == Mode.RUNNING || mode == Mode.PAUSED);
        settings.setProperty("running", Boolean.toString(running));
        settings.setProperty("durationMs", Long.toString(durationMs));
        settings.setProperty("endAtMs", Long.toString(mode == Mode.PAUSED ? System.currentTimeMillis() + pausedLeftMs : endAtMs));

        try {
            Files.createDirectories(settingsPath.getParent());
            try (OutputStream out = Files.newOutputStream(settingsPath)) {
                settings.store(out, "Hourglass settings");
            }
        } catch (IOException ignored) {
        }
    }

    private void addRecent(String value) {
        List<String> recents = getRecent();
        recents.remove(value);
        recents.add(0, value);
        while (recents.size() > 12) recents.remove(recents.size() - 1);
        settings.setProperty("recent", String.join("\n", recents));
    }

    private List<String> getRecent() {
        String raw = settings.getProperty("recent", "");
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\\n")) {
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static LinkedHashMap<String, String> createClockZones() {
        LinkedHashMap<String, String> zones = new LinkedHashMap<>();
        zones.put("East - Los Angeles", "America/Los_Angeles");
        zones.put("Center - Austin", "America/Chicago");
        zones.put("West - New York", "America/New_York");
        zones.put("UTC - London", "Europe/London");
        zones.put("ECT - Paris", "Europe/Paris");
        zones.put("ECT - Berlin", "Europe/Berlin");
        zones.put("ECT - Madrid", "Europe/Madrid");
        return zones;
    }

    private static LinkedHashMap<String, String> createDateFormats() {
        LinkedHashMap<String, String> fmts = new LinkedHashMap<>();
        fmts.put("Wed, Feb 26", "EEE, MMM d");
        fmts.put("26/02/2026", "dd/MM/yyyy");
        fmts.put("02/26/2026", "MM/dd/yyyy");
        fmts.put("26 Feb 2026", "d MMM yyyy");
        fmts.put("February 26, 2026", "MMMM d, yyyy");
        fmts.put("26.02.2026", "dd.MM.yyyy");
        return fmts;
    }

    private Path resolveSettingsPath() {
        // Favor APPDATA on Windows; fall back to user home for portability.
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "HourglassClone", "settings.properties");
        }
        return Paths.get(System.getProperty("user.home"), ".hourglass-clone", "settings.properties");
    }

    private long parseLong(String raw, long fallback) {
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static class RoundedBackgroundPanel extends JPanel {
        private final int arc;
        private final Color fill;

        RoundedBackgroundPanel(int arc, Color fill) {
            super(new BorderLayout());
            this.arc = arc;
            this.fill = fill;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
        }
    }

    private static class ParseResult {
        final long ms;

        ParseResult(long ms) {
            this.ms = ms;
        }
    }

    private static class FadePanel extends JPanel {
        private float alpha = 1f;

        FadePanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        float getAlpha() {
            return alpha;
        }

        void setAlpha(float value) {
            alpha = Math.max(0f, Math.min(1f, value));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            super.paintComponent(g2);
            g2.dispose();
        }

        @Override
        protected void paintChildren(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            super.paintChildren(g2);
            g2.dispose();
        }
    }

    private static class ProgressFramePanel extends JPanel {
        private final int thickness;
        private float progress = 0f;
        private boolean expired = false;

        ProgressFramePanel(int thickness) {
            super(new BorderLayout());
            this.thickness = thickness;
            setBorder(new EmptyBorder(thickness, thickness, thickness, thickness));
            setOpaque(false);
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            repaint();
        }

        void setExpired(boolean value) {
            expired = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();

            Color track = expired ? new Color(222, 186, 199) : new Color(221, 221, 221);
            Color fill = expired ? new Color(199, 80, 80) : new Color(63, 102, 172);

            drawProgressOnFrame(g2, track, 1f);
            drawProgressOnFrame(g2, fill, progress);
            g2.dispose();
        }

        private void drawProgressOnFrame(Graphics2D g2, Color color, float ratio) {
            if (ratio <= 0f) return;
            int w = getWidth();
            int h = getHeight();
            int t = thickness;
            if (w <= t * 2 || h <= t * 2) return;

            float topLen = w;
            float rightLen = h - t;
            float bottomLen = w - t;
            float leftLen = h - t;
            float perimeter = topLen + rightLen + bottomLen + leftLen;
            float remaining = Math.max(0f, Math.min(1f, ratio)) * perimeter;

            g2.setColor(color);

            // Draw clockwise along the frame edges: top -> right -> bottom -> left.
            float part = Math.min(remaining, topLen);
            if (part > 0) {
                g2.fillRect(0, 0, Math.round(part), t);
            }
            remaining -= part;

            part = Math.min(remaining, rightLen);
            if (part > 0) {
                g2.fillRect(w - t, t, t, Math.round(part));
            }
            remaining -= part;

            part = Math.min(remaining, bottomLen);
            if (part > 0) {
                int draw = Math.round(part);
                g2.fillRect(w - t - draw, h - t, draw, t);
            }
            remaining -= part;

            part = Math.min(remaining, leftLen);
            if (part > 0) {
                int draw = Math.round(part);
                g2.fillRect(0, h - t - draw, t, draw);
            }
        }
    }
}
