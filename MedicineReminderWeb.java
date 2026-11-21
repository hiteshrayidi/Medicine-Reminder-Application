import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.*;

public class MedicineReminderWeb {

    static final int SNOOZE_MIN = 5;          // snooze length
    static final int MISSED_AFTER_MIN = 30;   // unanswered reminder becomes "missed"
    static final int LOW_STOCK = 5;           // warn when pills left <= this
    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a");

    /* ---------------- Data classes ---------------- */

    enum Status {
        TAKEN, NOT_TAKEN, MISSED;
        String label() { return name().replace('_', ' '); }
    }

    static class Medicine implements Serializable {
        String id = UUID.randomUUID().toString();
        String name, dosage, notes = "";
        LocalTime time;
        Set<DayOfWeek> days = EnumSet.allOf(DayOfWeek.class);
        LocalDate endDate;               // null = no end date
        int stock = -1;                  // -1 = stock not tracked
        LocalDate lastPrompted;
        boolean pending;                 // waiting for the patient's answer
        LocalDateTime pendingSince, snoozeUntil;
    }

    static class Dose implements Serializable {
        String medId, medicine;
        LocalDate scheduled;
        LocalDateTime recordedAt;
        Status status;

        Dose(String medId, String medicine, LocalDate scheduled, LocalDateTime recordedAt, Status status) {
            this.medId = medId;
            this.medicine = medicine;
            this.scheduled = scheduled;
            this.recordedAt = recordedAt;
            this.status = status;
        }
    }

    static class User implements Serializable {
        String username, passwordHash;
        List<Medicine> medicines = new CopyOnWriteArrayList<>();
        List<Dose> history = new CopyOnWriteArrayList<>();

        User(String username, String passwordHash) {
            this.username = username;
            this.passwordHash = passwordHash;
        }
    }

    /* ---------------- Storage ---------------- */

    static final File FILE = new File("users.dat");
    static Map<String, User> users = new ConcurrentHashMap<>();
    static final Map<String, String> sessions = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static void load() {
        if (!FILE.exists()) return;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(FILE))) {
            users = (Map<String, User>) in.readObject();
        } catch (Exception e) {
            System.out.println("Could not read users.dat (delete it if it came from an older version).");
            e.printStackTrace();
        }
    }

    static synchronized void save() {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(FILE))) {
            out.writeObject(users);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* ---------------- Main ---------------- */

    public static void main(String[] args) throws Exception {
        load();

        // Use the first free port from 8080 to 8090. This avoids BindException
        // when another copy of this application is already using port 8080.
        HttpServer server = createAvailableServer(8080, 8090);
        int runningPort = server.getAddress().getPort();
        server.createContext("/", MedicineReminderWeb::home);
        server.createContext("/login", MedicineReminderWeb::login);
        server.createContext("/register", MedicineReminderWeb::register);
        server.createContext("/logout", MedicineReminderWeb::logout);
        server.createContext("/add", MedicineReminderWeb::add);
        server.createContext("/edit", MedicineReminderWeb::edit);
        server.createContext("/delete", MedicineReminderWeb::delete);
        server.createContext("/answer", MedicineReminderWeb::answer);
        server.createContext("/reminders", MedicineReminderWeb::reminders);
        server.createContext("/history", MedicineReminderWeb::history);
        server.createContext("/export", MedicineReminderWeb::export);
        server.createContext("/profile", MedicineReminderWeb::profile);

        // Background thread: creates reminders and marks unanswered ones as missed
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                LocalDateTime nowDT = LocalDateTime.now();
                LocalTime now = nowDT.toLocalTime().withSecond(0).withNano(0);
                LocalDate today = nowDT.toLocalDate();
                boolean changed = false;
                for (User u : users.values()) {
                    for (Medicine m : u.medicines) {
                        // Keep unanswered reminders pending; the alarm stops only after a user response.
                        if (!m.pending && m.time.equals(now) && !today.equals(m.lastPrompted)
                                && activeOn(m, today)) {
                            m.pending = true;
                            m.pendingSince = nowDT;
                            m.snoozeUntil = null;
                            m.lastPrompted = today;
                            changed = true;
                        }
                    }
                }
                if (changed) save();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 10, TimeUnit.SECONDS);

        server.start();
        System.out.println("Medicine Reminder System running at http://localhost:" + runningPort);
        System.out.println("Keep this terminal open while using the application.");
    }

    static HttpServer createAvailableServer(int firstPort, int lastPort) throws IOException {
        IOException lastError = null;
        for (int port = firstPort; port <= lastPort; port++) {
            try {
                return HttpServer.create(new InetSocketAddress(port), 0);
            } catch (java.net.BindException e) {
                lastError = e; // This port is already occupied; try the next one.
            }
        }
        throw new IOException("Ports " + firstPort + "-" + lastPort
                + " are already in use. Stop the other running server or free a port.", lastError);
    }

    /* ---------------- Logic helpers ---------------- */

    static boolean activeOn(Medicine m, LocalDate d) {
        return m.days.contains(d.getDayOfWeek()) && (m.endDate == null || !d.isAfter(m.endDate));
    }

    static String formatTime(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH));
    }

    static void record(User u, Medicine m, Status s) {
        LocalDate scheduled = m.lastPrompted != null ? m.lastPrompted : LocalDate.now();
        u.history.add(new Dose(m.id, m.name, scheduled, LocalDateTime.now(), s));
        if (s == Status.TAKEN && m.stock > 0) m.stock--;
    }

    static Status todayStatus(User u, Medicine m, LocalDate today) {
        Status s = null;
        for (Dose d : u.history) {
            if (d.medId.equals(m.id) && d.scheduled.equals(today)) s = d.status;
        }
        return s;
    }

    static Medicine findMed(User u, String id) {
        for (Medicine m : u.medicines) if (m.id.equals(id)) return m;
        return null;
    }

    static String adherence(User u) {
        int taken = 0, total = 0;
        for (Dose d : u.history) {
            total++;
            if (d.status == Status.TAKEN) taken++;
        }
        return total == 0 ? "N/A" : (taken * 100 / total) + "%";
    }

    static String daysLabel(Medicine m) {
        if (m.days.size() == 7) return "Every day";
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (m.days.contains(d)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            }
        }
        return sb.toString();
    }

    static String badge(User u, Medicine m, LocalDate today, LocalTime nowT) {
        if (!activeOn(m, today)) return "<span class='badge grey'>Not today</span>";
        Status s = todayStatus(u, m, today);
        if (s == Status.TAKEN) return "<span class='badge green'>Taken</span>";
        if (s == Status.NOT_TAKEN) return "<span class='badge red'>Not taken</span>";
        if (s == Status.MISSED) return "<span class='badge red'>Missed</span>";
        if (m.pending) return "<span class='badge amber'>Waiting for answer</span>";
        if (m.time.isAfter(nowT)) return "<span class='badge blue'>Upcoming</span>";
        return "<span class='badge grey'>-</span>";
    }

    // Reads and validates the form; throws if something is wrong (nothing is changed in that case)
    static void apply(Medicine m, Map<String, String> f) {
        String name = f.getOrDefault("name", "").trim();
        String dosage = f.getOrDefault("dosage", "").trim();
        if (name.isEmpty() || dosage.isEmpty()) throw new IllegalArgumentException("name/dosage required");
        // Ask the user to choose AM or PM explicitly instead of relying on a 24-hour time field.
        int hour12 = Integer.parseInt(f.getOrDefault("hour", ""));
        int minute = Integer.parseInt(f.getOrDefault("minute", ""));
        String meridiem = f.getOrDefault("meridiem", "").toUpperCase(Locale.ENGLISH);
        if (hour12 < 1 || hour12 > 12 || minute < 0 || minute > 59
                || !(meridiem.equals("AM") || meridiem.equals("PM"))) {
            throw new IllegalArgumentException("Enter a valid time and select AM or PM.");
        }
        int hour24 = hour12 % 12;
        if (meridiem.equals("PM")) hour24 += 12;
        LocalTime time = LocalTime.of(hour24, minute);
        String endS = f.getOrDefault("end", "").trim();
        LocalDate end = endS.isEmpty() ? null : LocalDate.parse(endS);
        String stockS = f.getOrDefault("stock", "").trim();
        int stock = stockS.isEmpty() ? -1 : Math.max(0, Integer.parseInt(stockS));
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (f.containsKey("d" + d.getValue())) days.add(d);
        }
        if (days.isEmpty()) days = EnumSet.allOf(DayOfWeek.class);

        if (m.time != null && !m.time.equals(time)) m.lastPrompted = null;  // allow a new reminder today
        m.name = name;
        m.dosage = dosage;
        m.notes = f.getOrDefault("notes", "").trim();
        m.time = time;
        m.endDate = end;
        m.stock = stock;
        m.days = days;
    }

    /* ---------------- Page handlers ---------------- */

    static void home(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, page("Not found", "<h2>Page not found</h2><a href='/'>Home</a>"));
            return;
        }
        User u = currentUser(ex);
        if (u == null) { redirect(ex, "/login"); return; }

        LocalDate today = LocalDate.now();
        LocalTime nowT = LocalTime.now();
        List<Medicine> sorted = new ArrayList<>(u.medicines);
        sorted.sort(Comparator.comparing(m -> m.time));

        int scheduled = 0, takenToday = 0;
        Medicine next = null;
        StringBuilder warnings = new StringBuilder(), rows = new StringBuilder();

        for (Medicine m : sorted) {
            Status ts = todayStatus(u, m, today);
            if (activeOn(m, today)) {
                scheduled++;
                if (ts == Status.TAKEN) takenToday++;
                if (ts == null && !m.pending && m.time.isAfter(nowT) && next == null) next = m;
            }
            if (m.stock >= 0 && m.stock <= LOW_STOCK) {
                warnings.append("<div class='warn'>").append(m.stock == 0 ? "Out of stock: " : "Low stock: ")
                        .append("<b>").append(esc(m.name)).append("</b> ")
                        .append(m.stock == 0 ? "- please refill." : "- only " + m.stock + " left.")
                        .append("</div>");
            }
            rows.append("<tr><td><b>").append(esc(m.name)).append("</b>");
            if (!m.notes.isEmpty()) rows.append("<br><small>").append(esc(m.notes)).append("</small>");
            rows.append("</td><td>").append(esc(m.dosage))
                .append("</td><td>").append(formatTime(m.time))
                .append("</td><td>").append(daysLabel(m));
            if (m.endDate != null) rows.append("<br><small>until ").append(m.endDate).append("</small>");
            rows.append("</td><td>").append(m.stock < 0 ? "-" : String.valueOf(m.stock))
                .append("</td><td>").append(badge(u, m, today, nowT))
                .append("</td><td><a class='btn' href='/edit?id=").append(m.id).append("'>Edit</a> ")
                .append("<form method='post' action='/delete' class='inline'>")
                .append("<input type='hidden' name='id' value='").append(m.id).append("'>")
                .append("<button class='del'>Delete</button></form></td></tr>");
        }
        if (sorted.isEmpty()) rows.append("<tr><td colspan='7'>No medicines added yet.</td></tr>");

        String nextText = next == null ? "-" : formatTime(next.time);
        String nextName = next == null ? "Nothing more today" : "Next: " + esc(next.name);

        String body = topbar("Welcome, " + esc(u.username))
            + "<iframe src='/reminders' class='frame'></iframe>"
            + "<div class='cards'>"
            + "<div class='card'><div class='big'>" + takenToday + "/" + scheduled + "</div>Doses taken today</div>"
            + "<div class='card'><div class='big'>" + adherence(u) + "</div>Overall adherence</div>"
            + "<div class='card'><div class='big'>" + nextText + "</div>" + nextName + "</div>"
            + "</div>"
            + warnings
            + "<details open><summary>Add a new medicine</summary>"
            + medForm("/add", null, "Add Medicine") + "</details>"
            + "<h3>My medicines</h3>"
            + "<table><tr><th>Medicine</th><th>Dosage</th><th>Time</th><th>Days</th><th>Stock</th>"
            + "<th>Today</th><th>Actions</th></tr>" + rows + "</table>";
        send(ex, 200, page("Dashboard", body));
    }

    static void login(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            if (currentUser(ex) != null) { redirect(ex, "/"); return; }
            String mode = query(ex).getOrDefault("mode", "login");
            send(ex, 200, loginPage(mode, "", false));
            return;
        }
        Map<String, String> f = form(ex);
        User u = users.get(f.getOrDefault("username", "").trim());
        if (u != null && u.passwordHash.equals(hash(f.getOrDefault("password", "")))) {
            String sid = UUID.randomUUID().toString();
            sessions.put(sid, u.username);
            ex.getResponseHeaders().add("Set-Cookie", "sid=" + sid + "; Path=/; HttpOnly");
            redirect(ex, "/");
        } else {
            send(ex, 200, loginPage("login", "Invalid username or password.", true));
        }
    }

    static void register(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { redirect(ex, "/login?mode=register"); return; }
        Map<String, String> f = form(ex);
        String name = f.getOrDefault("username", "").trim();
        String pw = f.getOrDefault("password", "");

        String error = null;
        if (name.length() < 3) error = "Username must be at least 3 characters.";
        else if (pw.length() < 6) error = "Password must be at least 6 characters.";
        else if (!pw.equals(f.getOrDefault("confirm", ""))) error = "Passwords do not match.";
        else if (users.containsKey(name)) error = "That username is already taken.";

        if (error != null) {
            send(ex, 200, loginPage("register", error, true));
            return;
        }
        users.put(name, new User(name, hash(pw)));
        save();
        send(ex, 200, loginPage("login", "Account created! Please log in.", false));
    }

    static void logout(HttpExchange ex) throws IOException {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c != null) {
            for (String part : c.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals("sid")) sessions.remove(kv[1]);
            }
        }
        ex.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; Max-Age=0");
        redirect(ex, "/login");
    }

    static void add(HttpExchange ex) throws IOException {
        User u = currentUser(ex);
        if (u == null) { redirect(ex, "/login"); return; }
        if (ex.getRequestMethod().equals("POST")) {
            try {
                Medicine m = new Medicine();
                apply(m, form(ex));
                u.medicines.add(m);
                save();
            } catch (RuntimeException ignored) { }
        }
        redirect(ex, "/");
    }

    static void edit(HttpExchange ex) throws IOException {
        User u = currentUser(ex);
        if (u == null) { redirect(ex, "/login"); return; }
        if (ex.getRequestMethod().equals("POST")) {
            Map<String, String> f = form(ex);
            Medicine m = findMed(u, f.getOrDefault("id", ""));
            if (m != null) {
                try {
                    apply(m, f);
                    save();
                } catch (RuntimeException ignored) { }
            }
            redirect(ex, "/");
            return;
        }
        Medicine m = findMed(u, query(ex).getOrDefault("id", ""));
        if (m == null) { redirect(ex, "/"); return; }
        String body = topbar("Edit medicine") + medForm("/edit", m, "Save changes")
                + "<p><a href='/'>Cancel</a></p>";
        send(ex, 200, page("Edit", body));
    }

    static void delete(HttpExchange ex) throws IOException {
        User u = currentUser(ex);
        if (u == null) { redirect(ex, "/login"); return; }
        if (ex.getRequestMethod().equals("POST")) {
            String id = form(ex).getOrDefault("id", "");
            u.medicines.removeIf(m -> m.id.equals(id));
            save();
        }
        redirect(ex, "/");
    }

    // Patient answers the reminder: taken / not taken / snooze
    static void answer(HttpExchange ex) throws IOException {
        User u = currentUser(ex);
        if (u == null) { redirect(ex, "/login"); return; }
        if (ex.getRequestMethod().equals("POST")) {
            Map<String, String> f = form(ex);
            Medicine m = findMed(u, f.getOrDefault("id", ""));
            String action = f.getOrDefault("action", "");
            if (m != null && m.pending) {
                if (action.equals("taken")) {
                    m.pending = false;
                    m.snoozeUntil = null;
                    record(u, m, Status.TAKEN);
                } else if (action.equals("not_taken")) {
                    m.pending = false;
                    m.snoozeUntil = null;
                    record(u, m, Status.NOT_TAKEN);
                } else if (action.equals("snooze")) {
                    m.snoozeUntil = LocalDateTime.now().plusMinutes(SNOOZE_MIN);
                }
                save();
            }
        }
        redirect(ex, "/");
    }

    // Reminder panel polls without reloading, so the alarm can continue ringing.
    static void reminders(HttpExchange ex) throws IOException {
        User u = currentUser(ex);
        StringBuilder sb = new StringBuilder();
        if (u != null) {
            LocalDateTime now = LocalDateTime.now();
            for (Medicine m : u.medicines) {
                boolean snoozed = m.snoozeUntil != null && now.isBefore(m.snoozeUntil);
                if (m.pending && !snoozed) {
                    sb.append("<div class='alert'>It's time to take <b>").append(esc(m.name))
                      .append("</b> (").append(esc(m.dosage)).append(") at <b>")
                      .append(formatTime(m.time)).append("</b>");
                    if (!m.notes.isEmpty()) sb.append(" - ").append(esc(m.notes));
                    sb.append(". Have you taken it?")
                      .append("<form method='post' action='/answer' target='_top'>")
                      .append("<input type='hidden' name='id' value='").append(m.id).append("'>")
                      .append("<button type='submit' name='action' value='taken' class='yes'>Yes, taken</button> ")
                      .append("<button type='submit' name='action' value='not_taken' class='no'>No</button> ")
                      .append("<button type='submit' name='action' value='snooze' class='snooze'>Snooze ")
                      .append(SNOOZE_MIN).append(" min</button>")
                      .append("</form></div>");
                }
            }
        }
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>" + CSS + "</style></head><body>"
                + "<div style='margin-bottom:8px'><button id='enableAlarm' type='button'>Enable Alarm Sound</button> "
                + "<small id='soundStatus'>Click once to allow reminder sounds.</small></div>"
                + "<div id='reminderArea'>" + sb + "</div><script>"
                + "let audioCtx=null,alarmTimer=null;"
                + "function beep(){if(!audioCtx)return;const o=audioCtx.createOscillator(),g=audioCtx.createGain();"
                + "o.type='sine';o.frequency.value=880;g.gain.value=0.16;o.connect(g);g.connect(audioCtx.destination);"
                + "o.start();o.stop(audioCtx.currentTime+0.35);}"
                + "function startAlarm(){if(!audioCtx||alarmTimer!==null)return;beep();alarmTimer=setInterval(beep,850);}"
                + "function stopAlarm(){if(alarmTimer!==null){clearInterval(alarmTimer);alarmTimer=null;}}"
                + "function syncAlarm(){if(document.querySelector('#reminderArea .alert'))startAlarm();else stopAlarm();}"
                + "document.getElementById('enableAlarm').addEventListener('click',async function(){try{"
                + "audioCtx=new(window.AudioContext||window.webkitAudioContext)();await audioCtx.resume();"
                + "this.textContent='Alarm Sound Enabled';document.getElementById('soundStatus').textContent='Alarm will ring until you respond.';"
                + "syncAlarm();}catch(e){document.getElementById('soundStatus').textContent='Unable to enable sound.';}});"
                + "async function refreshReminders(){try{const r=await fetch('/reminders',{cache:'no-store'});"
                + "const h=await r.text();const d=new DOMParser().parseFromString(h,'text/html');"
                + "const fresh=d.getElementById('reminderArea');if(fresh){document.getElementById('reminderArea').innerHTML=fresh.innerHTML;syncAlarm();}"
                + "}catch(e){}}"
                // Let the browser submit the form normally. Native form submission reliably
                // includes the clicked button's name/value and avoids iframe fetch issues.
                + "setInterval(refreshReminders,5000);syncAlarm();"
                + "</script></body></html>";
        send(ex, 200, html);
    }

    static void history(HttpExchange ex) throws IOException {
        User u = currentUser(ex);
        if (u == null) { redirect(ex, "/login"); return; }
        String filter = query(ex).getOrDefault("status", "ALL");

        int taken = 0, notTaken = 0, missed = 0;
        for (Dose d : u.history) {
            if (d.status == Status.TAKEN) taken++;
            else if (d.status == Status.NOT_TAKEN) notTaken++;
            else missed++;
        }

        List<Dose> list = new ArrayList<>(u.history);
        Collections.reverse(list);
        StringBuilder rows = new StringBuilder();
        for (Dose d : list) {
            if (!filter.equals("ALL") && !d.status.name().equals(filter)) continue;
            rows.append("<tr><td>").append(d.recordedAt.format(DT)).append("</td><td>")
                .append(esc(d.medicine)).append("</td><td>").append(d.status.label()).append("</td></tr>");
        }
        if (rows.length() == 0) rows.append("<tr><td colspan='3'>No records.</td></tr>");

        String[][] filters = {{"ALL", "All"}, {"TAKEN", "Taken"}, {"NOT_TAKEN", "Not taken"}, {"MISSED", "Missed"}};
        StringBuilder links = new StringBuilder();
        for (String[] x : filters) {
            links.append("<a class='btn").append(x[0].equals(filter) ? " active" : "")
                 .append("' href='/history?status=").append(x[0]).append("'>").append(x[1]).append("</a> ");
        }

        String body = topbar("History")
            + "<div class='cards'>"
            + "<div class='card'><div class='big'>" + taken + "</div>Taken</div>"
            + "<div class='card'><div class='big'>" + notTaken + "</div>Not taken</div>"
            + "<div class='card'><div class='big'>" + missed + "</div>Missed</div>"
            + "<div class='card'><div class='big'>" + adherence(u) + "</div>Adherence</div>"
            + "</div>"
            + "<p>" + links + " <a class='btn' href='/export'>Download CSV</a></p>"
            + "<table><tr><th>Date &amp; time</th><th>Medicine</th><th>Status</th></tr>" + rows + "</table>";
        send(ex, 200, page("History", body));
    }

    static void export(HttpExchange ex) throws IOException {
        User u = currentUser(ex);
        if (u == null) { redirect(ex, "/login"); return; }
        StringBuilder sb = new StringBuilder("Date,Time,Medicine,Status\n");
        for (Dose d : u.history) {
            sb.append(d.recordedAt.toLocalDate()).append(',')
              .append(d.recordedAt.toLocalTime().withNano(0)).append(",\"")
              .append(d.medicine.replace("\"", "\"\"")).append("\",")
              .append(d.status.label()).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"medicine_history.csv\"");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void profile(HttpExchange ex) throws IOException {
        User u = currentUser(ex);
        if (u == null) { redirect(ex, "/login"); return; }
        String msg = "";
        if (ex.getRequestMethod().equals("POST")) {
            Map<String, String> f = form(ex);
            String np = f.getOrDefault("newpw", "");
            if (!u.passwordHash.equals(hash(f.getOrDefault("current", "")))) {
                msg = "Current password is incorrect.";
            } else if (np.isEmpty() || !np.equals(f.getOrDefault("confirm", ""))) {
                msg = "New passwords do not match.";
            } else {
                u.passwordHash = hash(np);
                save();
                msg = "Password changed successfully.";
            }
        }
        String body = topbar("My Profile")
            + "<p>Username: <b>" + esc(u.username) + "</b></p>"
            + "<p>Medicines: " + u.medicines.size() + " | History records: " + u.history.size() + "</p>"
            + "<h3>Change password</h3><p class='msg'>" + esc(msg) + "</p>"
            + "<form method='post' action='/profile' class='grid'>"
            + "<label>Current password<input type='password' name='current' required></label>"
            + "<label>New password<input type='password' name='newpw' required></label>"
            + "<label>Confirm new password<input type='password' name='confirm' required></label>"
            + "<button>Change password</button></form>";
        send(ex, 200, page("Profile", body));
    }

    /* ---------------- HTML helpers ---------------- */

    static final String CSS = """
        body { font-family: Arial, sans-serif; background: #eef6fb; margin: 0; padding: 20px; }
        .box { max-width: 900px; margin: auto; background: white; padding: 24px;
               border-radius: 8px; box-shadow: 0 2px 8px #bbb; }
        .top { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; }
        a { color: #2a7fba; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; margin: 12px 0; }
        .grid label { display: flex; flex-direction: column; font-size: 13px; color: #444; }
        .grid input:not([type=checkbox]) { width: 100%; box-sizing: border-box; margin-top: 4px; }
        .days { grid-column: 1 / -1; font-size: 14px; }
        label.chk { display: inline; margin-right: 8px; }
        input { padding: 8px; border: 1px solid #aaa; border-radius: 4px; }
        button, .btn { padding: 8px 14px; background: #2a7fba; color: white; border: 0; border-radius: 4px;
                       cursor: pointer; text-decoration: none; font-size: 14px; display: inline-block; }
        button.del, button.no { background: #c0392b; }
        button.yes { background: #27ae60; }
        button.snooze { background: #7f8c8d; }
        .btn.active { background: #1b4f72; }
        form.inline { display: inline; }
        table { width: 100%; border-collapse: collapse; margin-top: 10px; }
        th, td { padding: 9px; border: 1px solid #ccc; text-align: left; vertical-align: top; }
        th { background: #2a7fba; color: white; }
        small { color: #666; }
        .cards { display: flex; gap: 12px; margin: 14px 0; flex-wrap: wrap; }
        .card { flex: 1; min-width: 150px; background: #f4f9fd; border: 1px solid #cfe3f1;
                border-radius: 8px; padding: 14px; text-align: center; color: #444; }
        .big { font-size: 26px; font-weight: bold; color: #1b4f72; }
        .alert { background: #fff3cd; border: 1px solid #e0a800; padding: 12px; border-radius: 6px; margin-bottom: 8px; }
        .alert form { display: block; margin-top: 8px; }
        .warn { background: #fdecea; border: 1px solid #e6a19a; padding: 8px 12px; border-radius: 6px; margin: 6px 0; }
        .badge { padding: 3px 8px; border-radius: 10px; font-size: 12px; color: white; }
        .green { background: #27ae60; } .red { background: #c0392b; } .amber { background: #e0a800; }
        .blue { background: #2a7fba; } .grey { background: #95a5a6; }
        .frame { width: 100%; height: 150px; border: 0; }
        .msg { color: #8a3b12; }
        summary { cursor: pointer; font-weight: bold; margin: 10px 0; }
        """;

    static final String LOGIN_CSS = """
        body.auth { margin: 0; min-height: 100vh; display: flex; align-items: center;
                    justify-content: center; padding: 20px; box-sizing: border-box;
                    background: linear-gradient(135deg, #2a7fba, #1abc9c);
                    font-family: Arial, sans-serif; }
        .auth-card { background: white; width: 100%; max-width: 380px; padding: 30px 28px;
                     border-radius: 14px; box-shadow: 0 10px 30px rgba(0,0,0,0.25); text-align: center; }
        .logo { font-size: 46px; }
        .auth-card h1 { margin: 6px 0 2px; color: #1b4f72; font-size: 24px; }
        .tagline { margin: 0 0 18px; color: #777; font-size: 14px; }
        .tabs { display: flex; background: #eef3f7; border-radius: 8px; padding: 4px; margin-bottom: 16px; }
        .tab { flex: 1; padding: 9px; text-decoration: none; color: #555; border-radius: 6px; font-size: 14px; }
        .tab.on { background: #2a7fba; color: white; font-weight: bold; }
        form { text-align: left; }
        label { display: block; font-size: 13px; color: #444; margin-bottom: 12px; }
        label input { width: 100%; box-sizing: border-box; padding: 10px; margin-top: 5px;
                      border: 1px solid #bbb; border-radius: 6px; font-size: 14px; }
        label input:focus { outline: none; border-color: #2a7fba; box-shadow: 0 0 0 2px rgba(42,127,186,0.2); }
        .submit { width: 100%; padding: 11px; background: #2a7fba; color: white; border: 0;
                  border-radius: 6px; font-size: 15px; cursor: pointer; }
        .submit:hover { background: #1b6a9e; }
        .note { padding: 9px 12px; border-radius: 6px; font-size: 13px; margin-bottom: 14px; text-align: left; }
        .note.bad { background: #fdecea; color: #a93226; border: 1px solid #e6a19a; }
        .note.good { background: #e8f8ef; color: #1e8449; border: 1px solid #a9dfbf; }
        .switch { font-size: 14px; color: #666; margin-top: 16px; }
        .switch a { color: #2a7fba; }
        .features { list-style: none; margin: 18px 0 0; padding: 14px 0 0; border-top: 1px solid #eee;
                    font-size: 12px; color: #888; }
        .features li { margin: 4px 0; }
        """;

    static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>" + esc(title)
                + " | Medicine Reminder</title><style>" + CSS + "</style></head><body>"
                + "<div class='box'>" + body + "</div></body></html>";
    }

    static String topbar(String heading) {
        return "<div class='top'><h2>" + heading + "</h2><span><a href='/'>Dashboard</a> | "
                + "<a href='/history'>History</a> | <a href='/profile'>Profile</a> | "
                + "<a href='/logout'>Logout</a></span></div>";
    }

    static String loginPage(String mode, String message, boolean error) {
        boolean reg = mode.equals("register");
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
          .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
          .append("<title>").append(reg ? "Register" : "Login").append(" | Medicine Reminder</title>")
          .append("<style>").append(LOGIN_CSS).append("</style></head><body class='auth'>")
          .append("<div class='auth-card'>")
          .append("<div class='logo'>&#128138;</div>")
          .append("<h1>Medicine Reminder</h1>")
          .append("<p class='tagline'>Never miss a dose again</p>")
          .append("<div class='tabs'><a class='tab").append(reg ? "" : " on").append("' href='/login'>Login</a>")
          .append("<a class='tab").append(reg ? " on" : "").append("' href='/login?mode=register'>Register</a></div>");

        if (!message.isEmpty()) {
            sb.append("<div class='note ").append(error ? "bad" : "good").append("'>")
              .append(esc(message)).append("</div>");
        }

        sb.append("<form method='post' action='").append(reg ? "/register" : "/login").append("'>")
          .append("<label>Username<input name='username' required autofocus ")
          .append(reg ? "minlength='3' " : "").append("placeholder='Enter your username'></label>")
          .append("<label>Password<input type='password' name='password' required ")
          .append(reg ? "minlength='6' " : "").append("placeholder='Enter your password'></label>");
        if (reg) {
            sb.append("<label>Confirm password<input type='password' name='confirm' required ")
              .append("placeholder='Re-enter your password'></label>");
        }
        sb.append("<button class='submit'>").append(reg ? "Create account" : "Login").append("</button></form>")
          .append("<p class='switch'>")
          .append(reg ? "Already have an account? <a href='/login'>Login</a>"
                      : "New here? <a href='/login?mode=register'>Create an account</a>")
          .append("</p>")
          .append("<ul class='features'><li>Timely medicine reminders</li>")
          .append("<li>Track taken &amp; missed doses</li><li>Stock and refill alerts</li></ul>")
          .append("</div></body></html>");
        return sb.toString();
    }

    // Used for both "add" (m == null) and "edit"
    static String medForm(String action, Medicine m, String button) {
        boolean edit = m != null;
        StringBuilder sb = new StringBuilder("<form method='post' action='" + action + "' class='grid'>");
        if (edit) sb.append("<input type='hidden' name='id' value='").append(m.id).append("'>");
        sb.append("<label>Medicine name<input name='name' required value='")
          .append(edit ? esc(m.name) : "").append("'></label>");
        sb.append("<label>Dosage<input name='dosage' required placeholder='e.g. 1 tablet' value='")
          .append(edit ? esc(m.dosage) : "").append("'></label>");
        int hour12 = edit ? (m.time.getHour() % 12 == 0 ? 12 : m.time.getHour() % 12) : 8;
        int minute = edit ? m.time.getMinute() : 0;
        String meridiem = edit ? (m.time.getHour() < 12 ? "AM" : "PM") : "AM";
        sb.append("<label>Hour (1-12)<input type='number' name='hour' min='1' max='12' required value='")
          .append(hour12).append("'></label>");
        sb.append("<label>Minute (00-59)<input type='number' name='minute' min='0' max='59' required value='")
          .append(String.format(Locale.ENGLISH, "%02d", minute)).append("'></label>");
        sb.append("<label>AM or PM<select name='meridiem' required>")
          .append("<option value='AM'").append(meridiem.equals("AM") ? " selected" : "").append(">AM</option>")
          .append("<option value='PM'").append(meridiem.equals("PM") ? " selected" : "").append(">PM</option>")
          .append("</select></label>");
        sb.append("<label>Notes / instructions<input name='notes' placeholder='e.g. after food' value='")
          .append(edit ? esc(m.notes) : "").append("'></label>");
        sb.append("<label>Pills in stock (optional)<input type='number' min='0' name='stock' value='")
          .append(edit && m.stock >= 0 ? String.valueOf(m.stock) : "").append("'></label>");
        sb.append("<label>Course end date (optional)<input type='date' name='end' value='")
          .append(edit && m.endDate != null ? m.endDate.toString() : "").append("'></label>");
        sb.append("<div class='days'>Days: ");
        for (DayOfWeek d : DayOfWeek.values()) {
            boolean checked = !edit || m.days.contains(d);
            sb.append("<label class='chk'><input type='checkbox' name='d").append(d.getValue())
              .append("' value='1'").append(checked ? " checked" : "").append("> ")
              .append(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).append("</label>");
        }
        sb.append("</div><button>").append(button).append("</button></form>");
        return sb.toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    /* ---------------- HTTP helpers ---------------- */

    static void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    static Map<String, String> parse(String s) {
        Map<String, String> map = new HashMap<>();
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        return parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    static Map<String, String> query(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return parse(q == null ? "" : q);
    }

    static User currentUser(HttpExchange ex) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String part : c.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("sid")) {
                String name = sessions.get(kv[1]);
                return name == null ? null : users.get(name);
            }
        }
        return null;
    }
}