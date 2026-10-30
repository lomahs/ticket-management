package tracker.adapters.fake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tracker.domain.Dates;
import tracker.domain.Holiday;
import tracker.domain.Leave;
import tracker.domain.LeaveType;
import tracker.domain.Member;
import tracker.domain.Naming;
import tracker.domain.Permission;
import tracker.domain.Role;
import tracker.domain.StepDef;
import tracker.domain.Steps;
import tracker.domain.WorkCalendar;

/**
 * Demo dataset: the ui-mock-v0 data expressed as Jira issues, plus the tracker-local data an admin would configure.
 *
 * <p>Everything is deterministic and anchored to DEMO_TODAY. Issues are generated as FakeIssue records that the
 * FakeJira adapter serves as IssueSnapshots, so the demo runs through the same sync pipeline as a real Jira site.
 */
public final class Dataset {
    private Dataset() {
    }

    public static final String DEMO_TODAY = "2026-10-07";
    static final String UPDATED = "2026-10-06T10:00:00+00:00";

    /** id, short name, full name, role, country, colour, email, permission, external. */
    record MemberRow(String id, String name, String fullName, Role role, String country, String color, String email,
                     Permission permission, boolean external) {
    }

    static final List<MemberRow> MEMBERS = List.of(
            new MemberRow("an", "An", "Nguyễn Thị An", Role.BA, "VN", "oklch(0.55 0.13 255)", "an.nguyen@acme.vn", Permission.USER, false),
            new MemberRow("binh", "Bình", "Trần Văn Bình", Role.BA, "VN", "oklch(0.52 0.13 305)", "binh.tran@acme.vn", Permission.USER, false),
            new MemberRow("chi", "Chi", "Lê Minh Chi", Role.BA, "VN", "oklch(0.56 0.14 35)", "chi.le@acme.vn", Permission.USER, false),
            new MemberRow("dung", "Dũng", "Phạm Quốc Dũng", Role.BA, "VN", "oklch(0.52 0.11 150)", "dung.pham@acme.vn", Permission.USER, false),
            new MemberRow("minh", "Minh", "Hoàng Minh", Role.BAL, "VN", "oklch(0.5 0.1 210)", "minh.hoang@acme.vn", Permission.ADMIN, false),
            new MemberRow("tanaka", "Tanaka", "Tanaka Hiroshi", Role.JM, "JP", "oklch(0.48 0.04 260)", "h.tanaka@client.co.jp", Permission.USER, true));

    static String accountOf(String memberId) {
        return "acc-" + memberId;
    }

    public static List<Member> demoMembers() {
        return MEMBERS.stream().map(m -> new Member(m.id(), m.name(), m.fullName(), m.role(), m.country(), m.color(), m.email(),
                m.permission(), m.external(), accountOf(m.id()))).toList();
    }

    private static final String[][] HOLIDAYS = {
            {"2026-09-01", "VN", "National Day holiday (VN)"}, {"2026-09-02", "VN", "National Day (VN)"},
            {"2026-09-21", "JP", "Respect for the Aged Day (JP)"}, {"2026-09-22", "JP", "Citizens' Holiday (JP)"},
            {"2026-09-23", "JP", "Autumnal Equinox Day (JP)"}, {"2026-10-12", "JP", "Sports Day (JP)"},
            {"2026-11-03", "JP", "Culture Day (JP)"}, {"2026-11-23", "JP", "Labor Thanksgiving Day (JP)"},
            {"2026-12-31", "JP", "Year-end holiday (JP)"}, {"2027-01-01", "VN JP", "New Year's Day"},
    };

    public static Map<String, Holiday> demoHolidays() {
        Map<String, Holiday> out = new LinkedHashMap<>();
        for (String[] h : HOLIDAYS) {
            out.put(h[0], new Holiday(h[0], List.of(h[1].split(" ")), h[2]));
        }
        return out;
    }

    private static final String[][] LEAVES = {
            {"chi", "2026-10-09", "full"}, {"minh", "2026-10-09", "full"}, {"an", "2026-10-16", "full"},
            {"dung", "2026-10-13", "full"}, {"binh", "2026-10-20", "half"}, {"tanaka", "2026-10-14", "halfTent"},
            {"an", "2026-10-22", "fullTent"}, {"an", "2026-10-23", "fullTent"}, {"chi", "2026-10-28", "fullTent"},
            {"dung", "2026-10-02", "half"}, {"binh", "2026-09-25", "full"}, {"minh", "2026-10-26", "fullTent"},
            {"tanaka", "2026-10-30", "full"}, {"chi", "2026-09-18", "full"},
    };

    public static List<Leave> demoLeaves() {
        List<Leave> out = new ArrayList<>();
        for (int i = 0; i < LEAVES.length; i++) {
            out.add(new Leave(i + 1, LEAVES[i][0], LEAVES[i][1], LeaveType.of(LEAVES[i][2]), ""));
        }
        return out;
    }

    public static final Map<String, String> EXTRA_DAYS = Map.of("2026-10-10", "Release support · go-live weekend");

    /** Epics on the simulated site: project, summary, due date. The first six are tracked; the last two can be added. */
    record Epic(String project, String summary, String dueDate) {
    }

    static final Map<String, Epic> EPICS = epics();

    private static Map<String, Epic> epics() {
        Map<String, Epic> e = new LinkedHashMap<>();
        e.put("PAY-1100", new Epic("PAY", "Payment flow revision", "2026-11-20"));
        e.put("PAY-1300", new Epic("PAY", "e-KYC integration", "2026-12-18"));
        e.put("PAY-1420", new Epic("PAY", "Revenue report upgrade", "2027-01-29"));
        e.put("PAY-0980", new Epic("PAY", "Rewards points system migration", "2026-10-16"));
        e.put("CRM-400", new Epic("CRM", "Customer Hub · Loyalty program", "2026-11-30"));
        e.put("OPS-200", new Epic("OPS", "Back-office · Daily operations", "2026-12-31"));
        e.put("PAY-1500", new Epic("PAY", "Refund automation", "2027-03-31"));
        e.put("CRM-450", new Epic("CRM", "Customer Hub · Campaigns", "2027-02-26"));
        return Collections.unmodifiableMap(e);
    }

    public static final List<String> TRACKED_EPICS = List.of("PAY-1100", "PAY-1300", "PAY-1420", "PAY-0980", "CRM-400", "OPS-200");

    /** N/W/R/C → (Jira status name, category). */
    static final Map<String, String[]> STATUS_NAMES = Map.of(
            "N", new String[]{"To Do", "new"}, "W", new String[]{"In Progress", "indeterminate"},
            "R", new String[]{"Resolved", "done"}, "C", new String[]{"Closed", "done"});
    /** Resolved sits in Jira's "done" category but is not closed for the tracker. */
    public static final Map<String, String> STATUS_BY_NAME = Map.of("Resolved", "R");

    // --- helpers ------------------------------------------------------------------------------------------------------

    static WorkCalendar calendar() {
        Map<String, Member> members = new LinkedHashMap<>();
        demoMembers().forEach(m -> members.put(m.id(), m));
        return new WorkCalendar(List.of(1, 2, 3, 4, 5), EXTRA_DAYS, demoHolidays(), members, demoLeaves());
    }

    /** The date n Mon–Fri days before d (so that Mon–Fri days in (result, d] == n). */
    static String weekdaysBefore(String d, int n) {
        while (n > 0) {
            d = Dates.addDays(d, -1);
            int w = Dates.weekday(d);
            if (w != 0 && w != 6) {
                n--;
            }
        }
        return d;
    }

    static final Map<String, String> STEP_NOTES = Map.of(
            "create", "Write spec", "balr", "Review spec", "fixb", "Address BAL comments", "jmr", "Review spec",
            "fixj", "Address JM comments");

    /** ui-mock-v0 worklog split: up to 4 entries walking back from the planned end (or today). */
    static List<FakeIssue.Log> splitLogs(String worker, double logged, String pe, String note) {
        List<FakeIssue.Log> out = new ArrayList<>();
        double left = logged;
        String d = pe != null && !pe.isEmpty() && pe.compareTo(DEMO_TODAY) < 0 ? pe : DEMO_TODAY;
        int i = 0;
        while (left > 0 && i < 4) {
            double h = Math.min(left, i == 0 && left > 4 ? left / 2 : 4);
            out.add(new FakeIssue.Log(d, worker, h, note));
            left -= h;
            d = Dates.addDays(d, -1);
            while (Dates.weekday(d) % 6 == 0) {
                d = Dates.addDays(d, -1);
            }
            i++;
        }
        return out;
    }

    /**
     * Fix-step worklogs for {@code rounds} review rounds: one BA entry per round with a 0.5h review by the BAL between rounds
     * (carved out of {@code logged}, so the total stays), on distinct weekdays walking back from the planned end (or today).
     * Fewer than two rounds: {@link #splitLogs}.
     */
    static List<FakeIssue.Log> fixLogs(String ba, String bal, double logged, int rounds, String pe, String note) {
        double review = 0.5;
        double baH = logged - review * (rounds - 1);
        if (rounds < 2 || baH < rounds * 0.5) {
            return splitLogs(ba, logged, pe, note);
        }
        double share = Math.floor(baH / rounds * 2) / 2;
        List<FakeIssue.Log> out = new ArrayList<>();
        String d = pe != null && !pe.isEmpty() && pe.compareTo(DEMO_TODAY) < 0 ? pe : DEMO_TODAY;
        for (int k = 0; k < 2 * rounds - 1; k++) {
            boolean baSlot = k % 2 == 0;
            double h = !baSlot ? review : k == 0 ? baH - share * (rounds - 1) : share;
            out.add(new FakeIssue.Log(d, baSlot ? ba : bal, h, baSlot ? note : "Review fixes"));
            d = Dates.addDays(d, -1);
            while (Dates.weekday(d) % 6 == 0) {
                d = Dates.addDays(d, -1);
            }
        }
        return out;
    }

    /** Changelog that reproduces review rounds and days held with the current assignee. */
    static List<FakeIssue.Change> handoverEvents(String status, String assignee, String owner, String reviewer, boolean fix,
                                                 int rounds, int held, String anchorDefault) {
        List<FakeIssue.Change> ev = new ArrayList<>();
        String anchor = held != 0 ? weekdaysBefore(DEMO_TODAY, held) : anchorDefault;
        if (status.equals("R") && held != 0) {
            ev.add(new FakeIssue.Change(anchor, "status", "W", "R"));
            anchor = weekdaysBefore(anchor, 1);
        }
        if (fix && (rounds >= 2 || held != 0)) {
            List<String[]> seq = new ArrayList<>();
            for (int k = 0; k < rounds; k++) {
                seq.add(new String[]{reviewer, owner});
                if (k < rounds - 1) {
                    seq.add(new String[]{owner, reviewer});
                }
            }
            if (!assignee.equals(owner)) {
                seq.add(new String[]{owner, assignee});
            }
            String d = anchor;
            List<FakeIssue.Change> dated = new ArrayList<>();
            for (int j = seq.size() - 1; j >= 0; j--) {
                dated.add(new FakeIssue.Change(d, "assignee", seq.get(j)[0], seq.get(j)[1]));
                d = weekdaysBefore(d, 1);
            }
            Collections.reverse(dated);
            dated.addAll(ev);
            ev = dated;
        } else if (held != 0 && !fix) {
            ev.add(0, new FakeIssue.Change(anchor, "assignee", !owner.equals(assignee) ? owner : reviewer, assignee));
        }
        ev.sort(Comparator.comparing(FakeIssue.Change::at));
        return ev;
    }

    static final class Keys {
        final String prefix;
        int n;
        final Set<String> reserved;

        Keys(String prefix, int start, Set<String> reserved) {
            this.prefix = prefix;
            this.n = start;
            this.reserved = reserved;
        }

        String next() {
            while (true) {
                String k = n < 1000 ? String.format("%s-%04d", prefix, n) : prefix + "-" + n;
                n++;
                if (reserved.add(k)) {
                    return k;
                }
            }
        }
    }

    static String summary(String phase, String fid, String name, String stepLabel) {
        Map<String, String> values = new HashMap<>();
        values.put("phase_name", phase);
        values.put("function_id", fid);
        values.put("function_name", name);
        values.put("step", stepLabel);
        return Naming.renderSummary(Naming.DEFAULT_RULE, values);
    }

    // --- PAY-1100: the anken shown in the Function Matrix of ui-mock-v0 ------------------------------------------------

    /** One step of a spec'd function: status, logged, estimate and options r (rounds), h (holder), d (held days), s/e (plan). */
    record StepSpec(String status, double logged, double est, Integer r, String h, Integer d, String s, String e) {
        StepSpec(String status, double logged, double est) {
            this(status, logged, est, null, null, null, null, null);
        }
    }

    private static StepSpec st(String status, double logged, double est) {
        return new StepSpec(status, logged, est);
    }

    private static StepSpec st(String status, double logged, double est, Integer r, String h, Integer d, String s, String e) {
        return new StepSpec(status, logged, est, r, h, d, s, e);
    }

    private static final StepSpec N0 = st("N", 0, 2);

    record FnSpec(String fid, String name, String ba, String start, List<StepSpec> steps) {
    }

    private static final List<FnSpec> SPEC = List.of(
            new FnSpec("F01", "Payment method selection", "an", "2026-09-14", List.of(st("C", 10, 10), st("C", 2, 2),
                    st("C", 3, 3, 1, null, null, null, null), st("C", 2, 2), st("C", 1.5, 2, 1, null, null, null, null))),
            new FnSpec("F02", "OTP verification", "an", "2026-09-16", List.of(st("C", 8, 8), st("C", 2, 2),
                    st("C", 2.5, 2, 2, null, null, null, null), st("C", 2, 2),
                    st("W", 1, 2, 1, null, 1, "2026-10-07", "2026-10-08"))),
            new FnSpec("F03", "Domestic card payment", "binh", "2026-09-21", List.of(st("C", 12, 10), st("C", 3, 2),
                    st("W", 7.5, 4, 3, null, 2, "2026-10-01", "2026-10-06"), N0, N0)),
            new FnSpec("F04", "E-wallet payment", "binh", "2026-09-18", List.of(st("C", 9, 10), st("C", 2, 2),
                    st("C", 2, 3, 1, null, null, null, null), st("W", 1, 2, null, null, 6, "2026-09-30", "2026-10-02"), N0)),
            new FnSpec("F05", "Partial refund", "chi", "2026-10-01", List.of(st("C", 8, 8),
                    st("W", 1, 2, null, null, 3, null, null), st("N", 0, 3), N0, N0)),
            new FnSpec("F06", "Transaction history", "chi", "2026-09-28", List.of(
                    st("W", 11, 8, null, null, null, null, "2026-10-05"), N0, N0, N0, N0)),
            new FnSpec("F07", "Payment result notification", "dung", "2026-09-14", List.of(st("C", 8, 8), st("C", 2, 2),
                    st("C", 3, 3, 2, null, null, null, null), st("C", 2, 2),
                    st("W", 5, 3, 3, "tanaka", 4, "2026-09-28", "2026-10-02"))),
            new FnSpec("F08", "End-of-day reconciliation", "dung", "2026-10-05", List.of(st("C", 6, 8),
                    st("W", 0.5, 2, null, null, 1, null, null), st("N", 0, 3), st("W", 0.5, 2, null, null, 1, null, null), N0)),
            new FnSpec("F09", "Payment gateway timeout handling", "an", "2026-10-12", List.of(st("N", 0, 8), N0, N0, N0, N0)),
            new FnSpec("F10", "Transaction fee configuration", "binh", "2026-09-24", List.of(st("C", 8, 8), st("C", 2, 2),
                    st("R", 3, 3, 2, "minh", 5, null, "2026-10-01"), N0, N0)),
            new FnSpec("F11", "Payment report export", "dung", "2026-10-01", List.of(st("C", 7, 8),
                    st("W", 1.5, 2, null, null, 4, null, null), N0, N0, N0)),
            new FnSpec("F12", "Retry failed payment", "chi", "2026-10-05", List.of(st("C", 7.5, 8), st("C", 0.5, 1),
                    st("W", 1, 3, 1, null, null, "2026-10-07", "2026-10-09"), N0, N0)));

    private record Done(String key, String title, String who, String ps, String pe, double est) {
    }

    private static final List<Done> PAY1100_PREPARE_DONE = List.of(
            new Done("PAY-1104", "Kick-off with JM", "minh", "2026-09-01", "2026-09-02", 4),
            new Done("PAY-1105", "Collect current payment flow", "an", "2026-09-01", "2026-09-03", 8),
            new Done("PAY-1106", "As-is flow diagram", "binh", "2026-09-02", "2026-09-04", 8),
            new Done("PAY-1107", "Gap analysis", "chi", "2026-09-03", "2026-09-07", 8),
            new Done("PAY-1108", "Screen list", "dung", "2026-09-04", "2026-09-08", 6),
            new Done("PAY-1109", "Message list", "an", "2026-09-07", "2026-09-09", 6),
            new Done("PAY-1110", "Error code inventory", "binh", "2026-09-08", "2026-09-10", 6),
            new Done("PAY-1111", "Function breakdown", "chi", "2026-09-09", "2026-09-11", 8),
            new Done("PAY-1112", "Estimate review", "minh", "2026-09-10", "2026-09-11", 4));

    /** key, title, phase parent, assignee, status, estimate, logged, start, end. */
    private record Extra(String key, String title, String parent, String who, String st, double est, double logged, String ps,
                         String pe) {
    }

    private static final List<Extra> PAY1100_EXTRAS = List.of(
            new Extra("PAY-1180", "Prepare Q&A list for JM", "PAY-1101", "an", "W", 4, 3, "2026-10-02", "2026-10-06"),
            new Extra("PAY-1261", "UT – F01 Payment method selection", "PAY-1103", "an", "W", 6, 2, "2026-10-06", "2026-10-07"),
            new Extra("PAY-1262", "UT – F02 OTP verification", "PAY-1103", "an", "N", 6, 0, "2026-10-09", "2026-10-09"),
            new Extra("PAY-1183", "Prepare domestic card test data", "PAY-1101", "binh", "W", 3, 1, "2026-10-06", "2026-10-07"),
            new Extra("PAY-1184", "Update card payment error screen", "PAY-1101", "binh", "W", 4, 5, "2026-10-01", "2026-10-05"),
            new Extra("PAY-1185", "Answer JM Q&A on card limits", "PAY-1101", "binh", "N", 2, 0, "2026-10-05", "2026-10-06"),
            new Extra("PAY-1186", "Cross-check payment gateway error codes", "PAY-1101", "binh", "W", 3, 1.5, "2026-10-02", "2026-10-02"),
            new Extra("PAY-1187", "Revise e-wallet flow diagram", "PAY-1101", "binh", "W", 2, 1, "2026-10-07", "2026-10-07"),
            new Extra("PAY-1188", "Review F10 handover checklist", "PAY-1101", "binh", "N", 1.5, 0, "2026-10-07", "2026-10-07"),
            new Extra("PAY-1265", "UT – F03 Domestic card payment", "PAY-1103", "binh", "W", 6, 2, "2026-10-06", "2026-10-07"),
            new Extra("PAY-1266", "UT – F10 Transaction fee configuration", "PAY-1103", "binh", "N", 5, 0, "2026-10-08", "2026-10-08"),
            new Extra("PAY-1189", "Prepare e-wallet test data", "PAY-1101", "binh", "N", 3, 0, "2026-10-08", "2026-10-08"),
            new Extra("PAY-1190", "Update fee mapping document", "PAY-1101", "binh", "N", 2, 0, "2026-10-08", "2026-10-08"),
            new Extra("PAY-1191", "Clarify refund requirements with JM", "PAY-1101", "binh", "N", 1, 0, "2026-10-08", "2026-10-08"),
            new Extra("PAY-1263", "UT – F04 E-wallet payment", "PAY-1103", "binh", "N", 6, 0, "2026-10-08", "2026-10-09"),
            new Extra("PAY-1181", "Update refund flow document", "PAY-1101", "chi", "W", 4, 2.5, "2026-10-06", "2026-10-08"),
            new Extra("PAY-1264", "UT – F07 Payment result notification", "PAY-1103", "dung", "N", 6, 0, "2026-10-14", "2026-10-15"),
            new Extra("PAY-1182", "Review Prepare overview document", "PAY-1101", "minh", "W", 3, 1, "2026-10-07", "2026-10-09"));

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    static List<FakeIssue> pay1100(WorkCalendar cal) {
        List<FakeIssue> out = new ArrayList<>(List.of(
                new FakeIssue("PAY-1101", "[Prepare] Payment flow revision", "PAY-1100", "W", "minh", "2026-09-01", "2026-10-09"),
                new FakeIssue("PAY-1102", "[Implement_Coding] Payment flow revision", "PAY-1100", "W", "minh", "2026-09-14", "2026-10-30"),
                new FakeIssue("PAY-1103", "[Function Unit Test] Payment flow revision", "PAY-1100", "N", "minh", "2026-11-02", "2026-11-20")));
        for (Done d : PAY1100_PREPARE_DONE) {
            out.add(new FakeIssue(d.key(), d.title(), "PAY-1101", "C", d.who(), d.ps(), d.pe(), d.est(), "2026-08-28",
                    splitLogs(d.who(), d.est(), d.pe(), "Work on task"), List.of()));
        }
        int k = 1201;
        for (FnSpec f : SPEC) {
            String cur = f.start();
            for (int si = 0; si < f.steps().size(); si++) {
                StepSpec o = f.steps().get(si);
                StepDef step = Steps.STEPS.get(si);
                String kind = step.kind().value();
                String reviewer = kind.equals("balr") ? "minh" : kind.equals("jmr") ? "tanaka" : null;
                String who = reviewer != null ? reviewer : o.h() != null ? o.h() : f.ba();
                String ps;
                String pe;
                if (o.e() != null) {
                    ps = o.s() != null ? o.s() : o.e();
                    pe = o.e();
                } else {
                    String frm = o.status().equals("N") && cur.compareTo(DEMO_TODAY) <= 0 ? Dates.addDays(DEMO_TODAY, 1) : cur;
                    String[] span = cal.span(frm, step.durationDays(), who);
                    ps = span[0];
                    pe = span[1];
                }
                cur = Dates.addDays(pe, 1);
                String worker = step.review() ? who : f.ba();
                String fixReviewer = kind.equals("fixj") ? "tanaka" : "minh";
                out.add(new FakeIssue(
                        "PAY-" + k, summary("Implement_Coding", f.fid(), f.name(), step.label()), "PAY-1102", o.status(), who, ps, pe,
                        o.est(), Dates.addDays(f.start(), -3),
                        step.fix() ? fixLogs(f.ba(), "minh", o.logged(), orZero(o.r()), pe, STEP_NOTES.get(kind))
                                : splitLogs(worker, o.logged(), pe, STEP_NOTES.get(kind)),
                        handoverEvents(o.status(), who, f.ba(), reviewer != null ? reviewer : fixReviewer, step.fix(),
                                orZero(o.r()), orZero(o.d()), ps)));
                k++;
            }
        }
        out.addAll(pay1100Daily());
        for (Extra x : PAY1100_EXTRAS) {
            out.add(new FakeIssue(x.key(), x.title(), x.parent(), x.st(), x.who(), x.ps(), x.pe(), x.est(), "2026-09-28",
                    x.logged() != 0 ? List.of(new FakeIssue.Log("2026-10-06", x.who(), x.logged(), "Work on task")) : List.of(),
                    List.of()));
        }
        return out;
    }

    // --- PAY-1100: what the Daily Overview lists besides functions ------------------------------------------------------

    /** Demo notes on the QnA and WorkRequest tickets: key → note. */
    public static final Map<String, String> NOTES = Map.of(
            "PAY-1171", "JM checking with the card team. Answer needed before F03 Fix comment BAL can close.",
            "PAY-1173", "Draft answer ready, An to send to JM after BAL check.",
            "PAY-1177", "Sent 12 messages for review. Tanaka expects to reply by 10/09.");

    private static FakeIssue.Change assigned(String at, String from, String to) {
        return new FakeIssue.Change(at, "assignee", from, to);
    }

    private static FakeIssue.Change moved(String at, String from, String to) {
        return new FakeIssue.Change(at, "status", from, to);
    }

    /**
     * Study tickets that share one name (one per BA, single tickets by the name rule's optional step), and the QnA and
     * WorkRequest Tasks, which the demo structure types.
     */
    static List<FakeIssue> pay1100Daily() {
        List<FakeIssue> out = new ArrayList<>(List.of(
                new FakeIssue("PAY-1160", "[Prepare] Requirement study", "PAY-1100", "C", "minh", "2026-09-03", "2026-09-04"),
                new FakeIssue("PAY-1165", "[Prepare] QnA management", "PAY-1100", "W", "minh", "2026-09-24", "2026-10-30"),
                new FakeIssue("PAY-1166", "[Prepare] Work requests", "PAY-1100", "W", "minh", "2026-10-05", "2026-10-30")));
        List<String> bas = List.of("an", "binh", "chi", "dung");
        for (int i = 0; i < bas.size(); i++) {
            out.add(new FakeIssue("PAY-116" + (i + 1), "[Prepare][ST] Requirement study", "PAY-1160", "C", bas.get(i), "2026-09-03",
                    "2026-09-04", 8, "2026-08-28", splitLogs(bas.get(i), 8, "2026-09-04", "Work on task"), List.of()));
        }
        out.add(new FakeIssue("PAY-1168", "Q10 · Timeout message wording", "PAY-1165", "C", "an", "2026-09-24", "2026-09-30", 1, "2026-09-24",
                List.of(new FakeIssue.Log("2026-09-30", "an", 1, "Answer QnA")),
                List.of(moved("2026-09-25", "N", "W"), moved("2026-10-05", "W", "C"))));
        out.add(new FakeIssue("PAY-1170", "Q11 · Fee rounding rule", "PAY-1165", "R", "tanaka", "2026-09-29", "2026-10-01", 1, "2026-09-29",
                List.of(new FakeIssue.Log("2026-10-01", "binh", 1, "Answer QnA")),
                List.of(moved("2026-09-30", "N", "W"), moved("2026-10-01", "W", "R"), assigned("2026-10-01", "binh", "tanaka"))));
        out.add(new FakeIssue("PAY-1171", "Q12 · Card limit per transaction", "PAY-1165", "W", "tanaka", "2026-10-01", "2026-10-08", 1, "2026-10-01",
                List.of(new FakeIssue.Log("2026-10-02", "binh", 0.5, "Ask JM")),
                List.of(moved("2026-10-02", "N", "W"), assigned("2026-10-02", "binh", "tanaka"))));
        out.add(new FakeIssue("PAY-1172", "Q13 · Refund window for e-wallet payments", "PAY-1165", "N", "tanaka", "2026-10-05", "2026-10-09", 1,
                "2026-10-05", List.of(), List.of(assigned("2026-10-05", "chi", "tanaka"))));
        out.add(new FakeIssue("PAY-1173", "Q14 · OTP resend limit and lock time", "PAY-1165", "W", "an", "2026-10-06", "2026-10-09", 1, "2026-10-06",
                List.of(new FakeIssue.Log("2026-10-06", "an", 0.5, "Draft answer")), List.of(moved("2026-10-06", "N", "W"))));
        out.add(new FakeIssue("PAY-1176", "Add refund reason codes to the spec", "PAY-1166", "W", "chi", "2026-10-05", "2026-10-09", 3, "2026-10-05",
                List.of(new FakeIssue.Log("2026-10-06", "chi", 1, "Work on task")), List.of(moved("2026-10-05", "N", "W"))));
        out.add(new FakeIssue("PAY-1177", "Confirm JP wording for error messages", "PAY-1166", "W", "tanaka", "2026-10-06", "2026-10-09", 2,
                "2026-10-06", List.of(), List.of(moved("2026-10-06", "N", "W"), assigned("2026-10-06", "dung", "tanaka"))));
        return out;
    }

    // --- other anken: generated sub-tasks shaped like their epic roll-ups ----------------------------------------------

    /** @param statuses one letter per step */
    record Fn(String fid, String name, String ba, String statuses) {
    }

    /** A pre-defined sub-task of another anken. */
    private record Fixed(String key, String status, String assignee, double est, double logged, String ps, String pe, int r, int d) {
    }

    private static final Map<String, Fixed> FIXED = Map.of(
            "PAY-1300/K04/2", new Fixed("PAY-1342", "W", "chi", 3, 4, "2026-09-30", "2026-10-02", 3, 3),
            "PAY-1300/K06/3", new Fixed("PAY-1351", "W", "tanaka", 2, 0.5, "2026-09-29", "2026-10-01", 0, 5),
            "PAY-0980/M04/4", new Fixed("PAY-0996", "W", "tanaka", 2, 3, "2026-09-25", "2026-09-30", 4, 3));

    private static double generatedLogged(String st, double est) {
        return st.equals("C") ? est : (st.equals("W") || st.equals("R")) ? est / 2 : 0;
    }

    static List<FakeIssue> phaseTasks(Keys keys, String parent, List<String> titles, List<String> who, String statuses,
                                      String start, String end, double est) {
        List<String> days = Dates.daysBetween(start, end).stream().filter(d -> Dates.weekday(d) != 0 && Dates.weekday(d) != 6).toList();
        List<FakeIssue> out = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            String ps = days.get(Math.min(days.size() - 1, i * Math.max(1, days.size() / Math.max(1, titles.size()))));
            String pe = days.get(Math.min(days.size() - 1, days.indexOf(ps) + 2));
            String st = String.valueOf(statuses.charAt(i));
            double logged = generatedLogged(st, est);
            String w = who.get(i % who.size());
            out.add(new FakeIssue(keys.next(), titles.get(i), parent, st, w, ps, pe, est, Dates.addDays(start, -5),
                    splitLogs(w, logged, pe, "Work on task"), List.of()));
        }
        return out;
    }

    static List<FakeIssue> functions(WorkCalendar cal, Keys keys, String anken, String parent, List<Fn> fns, String start,
                                     String reviewer, String jm) {
        String phase = "Implement_Coding";
        List<FakeIssue> out = new ArrayList<>();
        String day = start;
        for (int idx = 0; idx < fns.size(); idx++) {
            Fn f = fns.get(idx);
            String cur = cal.nextWork(day, f.ba());
            for (int si = 0; si < f.statuses().length(); si++) {
                String st = String.valueOf(f.statuses().charAt(si));
                StepDef step = Steps.STEPS.get(si);
                String kind = step.kind().value();
                Fixed fixed = FIXED.get(anken + "/" + f.fid() + "/" + si);
                String who = kind.equals("balr") ? reviewer : kind.equals("jmr") ? jm : f.ba();
                double est = si == 0 ? 8 : Math.floor(8 * step.effortWeight() * 2 + 0.5) / 2;
                String[] span = cal.span(cur, step.durationDays(), who);
                String ps = span[0];
                String pe = span[1];
                cur = Dates.addDays(pe, 1);
                if (fixed != null) {
                    st = fixed.status();
                    who = fixed.assignee();
                    est = fixed.est();
                    ps = fixed.ps();
                    pe = fixed.pe();
                }
                double logged = fixed != null ? fixed.logged() : generatedLogged(st, est);
                String worker = step.review() ? who : f.ba();
                int rounds = fixed != null ? fixed.r() : (step.fix() && !st.equals("N") ? 1 : 0);
                int held = fixed != null ? fixed.d() : 0;
                FakeIssue issue = new FakeIssue(
                        fixed != null ? fixed.key() : keys.next(), summary(phase, f.fid(), f.name(), step.label()), parent, st, who,
                        ps, pe, est, Dates.addDays(start, -3), step.fix() ? fixLogs(f.ba(), reviewer, logged, rounds, pe, STEP_NOTES.get(kind))
                                : splitLogs(worker, logged, pe, STEP_NOTES.get(kind)),
                        handoverEvents(st, who, f.ba(), kind.equals("fixj") ? jm : reviewer, step.fix(), rounds, held, ps));
                issue.reporter = reviewer; // the BAL files the function's sub-tasks
                out.add(issue);
            }
            day = Dates.addDays(day, idx % 2 != 0 ? 3 : 4);
        }
        return out;
    }

    private static List<String> range(String format, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(String.format(format, i));
        }
        return out;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    static List<FakeIssue> otherAnken(WorkCalendar cal, Set<String> reserved) {
        List<FakeIssue> out = new ArrayList<>();
        // PAY-1300 e-KYC integration: Prepare done, Implement about a third through, UT not started.
        Keys k = new Keys("PAY", 1304, reserved);
        out.add(new FakeIssue("PAY-1301", "[Prepare] e-KYC integration", "PAY-1300", "C", "minh", "2026-09-01", "2026-09-25"));
        out.add(new FakeIssue("PAY-1302", "[Implement_Coding] e-KYC integration", "PAY-1300", "W", "minh", "2026-09-28", "2026-11-27"));
        out.add(new FakeIssue("PAY-1303", "[Function Unit Test] e-KYC integration", "PAY-1300", "N", "minh", "2026-11-30", "2026-12-18"));
        out.addAll(phaseTasks(k, "PAY-1301", List.of("e-KYC vendor comparison", "Regulation checklist", "Customer journey map",
                        "Screen list", "Message list", "Error code list", "Function breakdown", "Estimate review"),
                List.of("an", "chi", "minh"), "CCCCCCCC", "2026-09-01", "2026-09-25", 6));
        out.addAll(functions(cal, k, "PAY-1300", "PAY-1302", List.of(
                new Fn("K01", "ID card upload", "an", "CCCCC"), new Fn("K02", "OCR result review", "chi", "CCCCC"),
                new Fn("K03", "Liveness check", "an", "CCCWN"), new Fn("K04", "ID card capture", "chi", "CCWNN"),
                new Fn("K05", "Address verification", "an", "CWNNN"), new Fn("K06", "Face matching", "chi", "CCCWN"),
                new Fn("K07", "Risk scoring", "an", "WNNNN"), new Fn("K08", "Manual review queue", "chi", "NNNNN")),
                "2026-09-14", "minh", "tanaka"));
        out.addAll(phaseTasks(k, "PAY-1303", concat(range("UT – K0%d test cases", 1, 8),
                        List.of("UT – Regression pack", "UT – Vendor sandbox run", "UT – Evidence review", "UT – Sign-off")),
                List.of("an", "chi"), "N".repeat(12), "2026-11-30", "2026-12-18", 6));
        // PAY-1420 Revenue report upgrade: Prepare under way, nothing implemented yet.
        k = new Keys("PAY", 1424, reserved);
        out.add(new FakeIssue("PAY-1421", "[Prepare] Revenue report upgrade", "PAY-1420", "W", "minh", "2026-09-28", "2026-10-16"));
        out.add(new FakeIssue("PAY-1422", "[Implement_Coding] Revenue report upgrade", "PAY-1420", "N", "minh", "2026-10-19", "2026-12-18"));
        out.add(new FakeIssue("PAY-1423", "[Function Unit Test] Revenue report upgrade", "PAY-1420", "N", "minh", "2026-12-21", "2027-01-29"));
        out.addAll(phaseTasks(k, "PAY-1421", List.of("Current report inventory", "KPI definitions", "Data source mapping",
                "Report mock-ups", "Estimate review"), List.of("dung", "binh", "minh"), "CCCWW", "2026-09-28", "2026-10-16", 6));
        String[][] reports = {{"Daily revenue summary", "dung"}, {"Revenue by channel", "binh"}, {"Refund adjustments", "dung"},
                {"Fee breakdown", "binh"}, {"Monthly close report", "dung"}, {"Export to accounting", "binh"}, {"Report scheduler", "dung"}};
        List<Fn> reportFns = new ArrayList<>();
        for (int i = 0; i < reports.length; i++) {
            reportFns.add(new Fn("R0" + (i + 1), reports[i][0], reports[i][1], "NNNNN"));
        }
        out.addAll(functions(cal, k, "PAY-1420", "PAY-1422", reportFns, "2026-10-19", "minh", "tanaka"));
        out.addAll(phaseTasks(k, "PAY-1423", concat(range("UT – R0%d test cases", 1, 7),
                        List.of("UT – Data reconciliation", "UT – Performance check", "UT – Sign-off")),
                List.of("dung", "binh"), "N".repeat(10), "2026-12-21", "2027-01-29", 6));
        // PAY-0980 Rewards points system migration: implemented, finishing UT.
        k = new Keys("PAY", 981, reserved);
        reserved.addAll(List.of("PAY-0996", "PAY-1009", "PAY-1012", "PAY-1015"));
        out.add(new FakeIssue("PAY-0981", "[Prepare] Rewards points system migration", "PAY-0980", "C", "minh", "2026-07-01", "2026-07-31"));
        out.add(new FakeIssue("PAY-0982", "[Implement_Coding] Rewards points system migration", "PAY-0980", "C", "minh", "2026-08-03", "2026-09-18"));
        out.add(new FakeIssue("PAY-0983", "[Function Unit Test] Rewards points system migration", "PAY-0980", "W", "minh", "2026-09-21", "2026-10-16"));
        k.n = 984;
        out.addAll(phaseTasks(k, "PAY-0981", List.of("Legacy points audit", "Migration rules", "Partner list", "Cut-over plan",
                "Rollback plan", "Estimate review"), List.of("binh", "chi", "dung"), "CCCCCC", "2026-07-01", "2026-07-31", 6));
        List<String> names = List.of("Points accrual rules", "Points balance view", "Points transfer", "Points accrual", "Tier upgrade",
                "Tier downgrade", "Partner points sync", "Points expiry job", "Points redemption", "Statement export", "Points expiry");
        List<String> bas = List.of("binh", "chi", "dung");
        List<Fn> pointFns = new ArrayList<>();
        for (int i = 1; i <= names.size(); i++) {
            pointFns.add(new Fn(String.format("M%02d", i), names.get(i - 1), bas.get(i % 3), i == 4 ? "CCCCW" : "CCCCC"));
        }
        out.addAll(functions(cal, k, "PAY-0980", "PAY-0982", pointFns, "2026-08-03", "minh", "tanaka"));
        List<String> utTitles = new ArrayList<>();
        for (int i = 1; i <= names.size(); i++) {
            if (i != 6 && i != 9 && i != 11) {
                utTitles.add(String.format("UT – M%02d %s", i, names.get(i - 1)));
            }
        }
        List<FakeIssue> ut = new ArrayList<>(phaseTasks(k, "PAY-0983", utTitles, bas, "CCCCCCCC", "2026-09-21", "2026-10-02", 6));
        ut.add(new FakeIssue("PAY-1012", "UT – M09 Points redemption", "PAY-0983", "W", "binh", "2026-09-28", "2026-10-02", 8,
                "2026-07-01", List.of(new FakeIssue.Log("2026-10-05", "binh", 9, "Work on task")), List.of()));
        ut.add(new FakeIssue("PAY-1015", "UT – M11 Points expiry", "PAY-0983", "W", "dung", "2026-10-01", "2026-10-05", 6,
                "2026-07-01", List.of(new FakeIssue.Log("2026-10-05", "dung", 4, "Work on task")), List.of()));
        ut.add(new FakeIssue("PAY-1009", "UT – M06 Partner points sync", "PAY-0983", "R", "chi", "2026-09-24", "2026-09-29", 6,
                "2026-07-01", List.of(new FakeIssue.Log("2026-10-05", "chi", 6, "Work on task")),
                List.of(new FakeIssue.Change(weekdaysBefore(DEMO_TODAY, 4), "status", "W", "R"))));
        out.addAll(ut);
        return out;
    }

    // --- CRM / OPS: tasks that do not follow the function naming rule ---------------------------------------------------

    private record Other(String key, String title, String who, String ps, String pe, double est, double logged, List<String> act) {
    }

    private static final List<Other> OTHER = List.of(
            new Other("CRM-412", "Customer segment rules – Create", "an", "2026-10-05", "2026-10-09", 16, 8, List.of("2026-10-06")),
            new Other("CRM-418", "Loyalty tier spec – BAL Review", "minh", "2026-10-12", "2026-10-13", 4, 0, null),
            new Other("CRM-405", "Contact merge flow – Create", "binh", "2026-09-21", "2026-09-25", 14, 15, List.of("2026-09-22", "2026-09-28")),
            new Other("CRM-430", "Campaign export – Create", "binh", "2026-10-12", "2026-10-16", 16, 0, null),
            new Other("OPS-221", "Refund approval screen – Create", "chi", "2026-10-13", "2026-10-16", 12, 0, null),
            new Other("OPS-219", "Daily ops report – Create", "dung", "2026-09-28", "2026-10-02", 12, 12, List.of("2026-09-28", "2026-10-02")),
            new Other("OPS-225", "Ops dashboard – JM Review", "tanaka", "2026-10-08", "2026-10-09", 3, 0, null),
            new Other("OPS-230", "Role permissions – Create", "dung", "2026-10-19", "2026-10-23", 16, 0, null),
            new Other("CRM-433", "Segment rules – JM Review", "tanaka", "2026-10-19", "2026-10-20", 3, 0, null),
            new Other("OPS-226", "Daily ops report – BAL Review", "minh", "2026-10-05", "2026-10-06", 3, 3, List.of("2026-10-05", "2026-10-06")),
            new Other("CRM-420", "Loyalty tier spec – Create", "an", "2026-09-28", "2026-10-02", 14, 13, List.of("2026-09-28", "2026-10-02")),
            new Other("OPS-232", "Audit log export – Create", "chi", "2026-10-19", "2026-10-22", 10, 0, null));
    static final String MEETING_KEY = "OPS-201";

    static List<FakeIssue> crmOps() {
        List<FakeIssue> out = new ArrayList<>(List.of(
                new FakeIssue("CRM-401", "[Implement_Coding] Customer Hub · Loyalty program", "CRM-400", "W", "minh", "2026-09-21", "2026-11-30"),
                new FakeIssue("OPS-205", "[Implement_Coding] Back-office · Daily operations", "OPS-200", "W", "minh", "2026-09-28", "2026-12-31"),
                new FakeIssue("OPS-210", "[Operation] Team operations", "OPS-200", "W", "minh", null, null),
                new FakeIssue(MEETING_KEY, "Internal meeting / support", "OPS-210", "W", "minh", null, null, 0, "2026-08-31")));
        for (Other o : OTHER) {
            String parent = o.key().startsWith("CRM") ? "CRM-401" : "OPS-205";
            List<FakeIssue.Log> logs = new ArrayList<>();
            if (o.act() != null && !o.act().isEmpty()) {
                if (o.act().size() == 1) {
                    logs.add(new FakeIssue.Log(o.act().get(0), o.who(), o.logged(), "Work on task"));
                } else {
                    logs.add(new FakeIssue.Log(o.act().get(0), o.who(), o.logged() / 2, "Work on task"));
                    logs.add(new FakeIssue.Log(o.act().get(1), o.who(), o.logged() - o.logged() / 2, "Work on task"));
                }
            }
            String status = o.act() != null && o.act().size() > 1 ? "C" : o.logged() != 0 ? "W" : "N";
            out.add(new FakeIssue(o.key(), o.title(), parent, status, o.who(), o.ps(), o.pe(), o.est(), "2026-09-14", logs, List.of()));
        }
        return out;
    }

    // --- timesheet fill: meetings and support logged on the operations ticket ------------------------------------------

    private static final double[] PAT = {8, 8, 8, 7.5, 8, 8, 6.5, 8, 9.5, 8, 8, 0, 8, 8.5, 8, 7, 8, 10};
    private static final Map<String, double[]> WEEK = Map.of(
            "an", new double[]{8, 8, 4}, "binh", new double[]{8, 7.5, 3}, "chi", new double[]{7.5, 8, 2.5},
            "dung", new double[]{8, 6, 3}, "minh", new double[]{8, 8, 5}, "tanaka", new double[]{7, 7.5, 4});

    static double targetHours(String memberId, String d, WorkCalendar cal) {
        double h;
        if (d.compareTo("2026-10-05") >= 0) {
            int i = Dates.dayDiff("2026-10-05", d);
            h = i < 3 ? WEEK.get(memberId)[i] : 0;
        } else {
            int n = Integer.parseInt(d.substring(8)) + Integer.parseInt(d.substring(5, 7)) * 31;
            h = PAT[(n * 7 + memberId.length() * 3 + memberId.codePointAt(0)) % PAT.length];
        }
        Leave lv = cal.leave(memberId, d);
        return lv != null && lv.type() == LeaveType.HALF ? Math.min(h, 4) : h;
    }

    static void fillMeetings(List<FakeIssue> issues, WorkCalendar cal) {
        Map<String, Double> logged = new HashMap<>();
        for (FakeIssue i : issues) {
            for (FakeIssue.Log w : i.worklogs) {
                logged.merge(w.member() + "\u0000" + w.date(), w.hours(), Double::sum);
            }
        }
        FakeIssue meeting = issues.stream().filter(i -> i.key.equals(MEETING_KEY)).findFirst().orElseThrow();
        for (MemberRow m : MEMBERS) {
            for (String d : Dates.daysBetween("2026-09-01", DEMO_TODAY)) {
                if (cal.off(d, m.id()) != null) {
                    continue;
                }
                double gap = targetHours(m.id(), d, cal) - logged.getOrDefault(m.id() + "\u0000" + d, 0.0);
                if (gap > 0) {
                    meeting.worklogs.add(new FakeIssue.Log(d, m.id(), gap, "Meeting / support"));
                }
            }
        }
    }

    public static List<FakeIssue> buildIssues() {
        WorkCalendar cal = calendar();
        List<FakeIssue> issues = new ArrayList<>(pay1100(cal));
        Set<String> reserved = new java.util.HashSet<>();
        issues.forEach(i -> reserved.add(i.key));
        reserved.addAll(List.of("PAY-1342", "PAY-1351"));
        issues.addAll(otherAnken(cal, reserved));
        issues.addAll(crmOps());
        fillMeetings(issues, cal);
        issues.stream().filter(i -> NO_LABEL.contains(i.key)).forEach(i -> i.labels = List.of());
        return issues;
    }

    /** Sub-tasks someone forgot to label, for the Ticket Check page. */
    static final Set<String> NO_LABEL = Set.of("PAY-1173", "PAY-1188", "PAY-1265");
}
