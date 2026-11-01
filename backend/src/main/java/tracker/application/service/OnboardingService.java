package tracker.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.MasterScheduleRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.OnboardingRepository;
import tracker.application.service.AllocationService.AllocationChange;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.BatchResult;
import tracker.application.service.TicketService.NewTicket;
import tracker.domain.Allocations;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.Issue;
import tracker.domain.Member;
import tracker.domain.Onboarding;
import tracker.domain.TaskNames;
import tracker.domain.TaskTemplate;
import tracker.domain.Text;
import tracker.domain.Viewer;
import tracker.domain.WorkStructure;

/**
 * Onboarding a new anken, step by step: track its epic, create the template Tasks, then estimation, members, master schedule
 * and detail plan (their own services). Each step's data is saved by that step; this keeps how far the anken got.
 */
@Service
public class OnboardingService {
    private final OnboardingRepository repo;
    private final JiraSettingsService jira;
    private final TrackerContextLoader context;
    private final AnkenRepository anken;
    private final AnkenMemberRepository ankenMembers;
    private final AnkenMembersService membersService;
    private final MemberRepository members;
    private final AllocationRepository allocationRows;
    private final AllocationService allocations;
    private final EstimationRepository estimation;
    private final MasterScheduleRepository versions;
    private final TicketService tickets;
    private final Clock clock;

    public OnboardingService(OnboardingRepository repo, JiraSettingsService jira, TrackerContextLoader context, AnkenRepository anken,
                             AnkenMemberRepository ankenMembers, AnkenMembersService membersService, MemberRepository members,
                             AllocationRepository allocationRows, AllocationService allocations, EstimationRepository estimation,
                             MasterScheduleRepository versions, TicketService tickets, Clock clock) {
        this.repo = repo;
        this.jira = jira;
        this.context = context;
        this.anken = anken;
        this.ankenMembers = ankenMembers;
        this.membersService = membersService;
        this.members = members;
        this.allocationRows = allocationRows;
        this.allocations = allocations;
        this.estimation = estimation;
        this.versions = versions;
        this.tickets = tickets;
        this.clock = clock;
    }

    /** @param step the furthest step reached (1–6) */
    public record OnboardingView(String epicKey, String title, String ankenPrefix, int step, String startedAt, String startedBy,
                                 String savedAt, String savedBy, @Nullable String finishedAt) {
    }

    /** @param onboarding whether the epic is being (or was) onboarded already */
    public record EpicLookup(String key, String title, String project, boolean tracked, boolean onboarding) {
    }

    /**
     * A Task of the anken's template.
     *
     * @param existingKey the Task of the epic that matches it; null while it is missing
     * @param name        the Task's Summary when it exists, else the proposed name of the new Task
     */
    public record TemplateTask(String match, String phase, @Nullable String existingKey, String name) {
    }

    public record TemplateTasksView(String ankenPrefix, String taskRule, List<TemplateTask> tasks) {
    }

    /** A missing template Task to create, by its template's match, with the name it gets. */
    public record NewTaskIn(String match, String name) {
    }

    /** @param hoursPerDay on this anken today; null without an allocation */
    public record MemberHours(String memberId, String name, @Nullable String role, String country, boolean inAnken,
                              @Nullable Double hoursPerDay) {
    }

    /** @param hoursPerDay null leaves the allocation as it is */
    public record MemberIn(String memberId, boolean inAnken, @Nullable Double hoursPerDay) {
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private OnboardingView view(Onboarding o) {
        String title = anken.findAll().stream().filter(a -> a.key().equals(o.epicKey())).map(Anken::title).findFirst().orElse("");
        return new OnboardingView(o.epicKey(), title, o.ankenPrefix(), o.step(), o.startedAt(), o.startedBy(), o.savedAt(), o.savedBy(),
                o.finishedAt());
    }

    private Onboarding require(String epic) {
        return repo.find(epic).orElseThrow(() -> new NotFound("Anken " + epic + " is not being onboarded", "epic"));
    }

    /** Most recently saved first. */
    public List<OnboardingView> list() {
        return repo.findAll().stream().map(this::view).toList();
    }

    public OnboardingView get(String epic) {
        return view(require(epic));
    }

    public EpicLookup lookup(String key) {
        JiraSettingsService.EpicLookup e = jira.lookupEpic(key);
        return new EpicLookup(e.key(), e.title(), e.project(), e.tracked(), repo.find(e.key()).isPresent());
    }

    /**
     * Step 1: tracks the epic when it is not yet (a full sync) and starts its onboarding at step 2; the PIM becomes a member of
     * the anken. An epic being onboarded already resumes. Not one transaction: tracking runs a sync.
     */
    public OnboardingView start(Viewer actor, String key) {
        JiraSettingsService.EpicLookup e = jira.lookupEpic(key);
        if (!e.tracked()) {
            jira.addEpic(e.key());
        }
        Onboarding existing = repo.find(e.key()).orElse(null);
        if (existing != null) {
            return view(existing);
        }
        String at = now();
        if (actor.memberId() != null && members.findById(actor.memberId()).isPresent()) {
            ankenMembers.add(e.key(), actor.memberId(), actor.email(), at);
        }
        Onboarding o = new Onboarding(e.key(), "", 2, at, actor.email(), at, actor.email(), null);
        repo.save(o);
        return view(o);
    }

    /** Records progress: the furthest of the saved step and {@code step}; a null prefix keeps the saved one. */
    public OnboardingView save(Viewer actor, String epic, int step, @Nullable String ankenPrefix) {
        if (step < 1 || step > Onboarding.STEPS) {
            throw new Invalid("Step must be between 1 and " + Onboarding.STEPS, "step");
        }
        Onboarding o = require(epic);
        Onboarding next = new Onboarding(epic, ankenPrefix == null ? o.ankenPrefix() : ankenPrefix.strip(), Math.max(o.step(), step), o.startedAt(),
                o.startedBy(), now(), actor.email(), o.finishedAt());
        repo.save(next);
        return view(next);
    }

    /**
     * Stops the onboarding: its record and the estimation rows not in Jira go, and the master schedule too while nothing of the
     * plan was pushed. The epic stays tracked; Tasks in Jira, members and allocations stay.
     */
    @Transactional
    public void discard(String epic) {
        require(epic);
        repo.delete(epic);
        estimation.deleteUnpushed(epic);
        if (estimation.findByEpic(epic).isEmpty()) {
            versions.deleteByEpic(epic);
        }
    }

    /** The last step: refused while estimation rows are not in Jira. */
    public OnboardingView finish(Viewer actor, String epic) {
        Onboarding o = require(epic);
        long open = estimation.findByEpic(epic).stream().filter(r -> !r.pushed()).count();
        if (open > 0) {
            throw new Invalid("Push the detail plan first: " + open + " row" + (open == 1 ? " is" : "s are") + " not in Jira", "plan");
        }
        Onboarding done = new Onboarding(epic, o.ankenPrefix(), Onboarding.STEPS, o.startedAt(), o.startedBy(), now(), actor.email(), now());
        repo.save(done);
        return view(done);
    }

    // --- step 2: template Tasks --------------------------------------------------------------------------------------------

    private TrackerContext load(String epic) {
        return EstimationService.load(context, new AnkenScope(Set.of(epic)), epic);
    }

    /** @param ankenPrefix null: the saved one */
    public TemplateTasksView tasks(String epic, @Nullable String ankenPrefix) {
        String prefix = ankenPrefix != null ? ankenPrefix.strip() : require(epic).ankenPrefix();
        TrackerContext ctx = load(epic);
        WorkStructure st = ctx.settings.structureOf(epic);
        String rule = Text.orElse(ctx.settings.nameRulesOf(epic).taskRule, TaskNames.DEFAULT_RULE);
        List<Issue> parents = ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList();
        List<TemplateTask> out = new ArrayList<>();
        for (TaskTemplate t : st.tasks()) {
            Issue found = parents.stream().filter(p -> t.equals(st.taskTemplate(p.summary()))).findFirst().orElse(null);
            out.add(new TemplateTask(t.match(), t.phase(), found == null ? null : found.key(),
                    found == null ? TaskNames.propose(rule, prefix, t, st) : found.summary()));
        }
        return new TemplateTasksView(prefix, rule, out);
    }

    /** The new Tasks, checked: each names a missing template Task and is recognised by it. */
    private List<NewTicket> newTasks(String epic, String ankenPrefix, String assigneeId, List<NewTaskIn> in) {
        if (ankenPrefix == null || ankenPrefix.isBlank()) {
            throw new Invalid("Enter the anken prefix", "anken_prefix");
        }
        if (in.isEmpty()) {
            throw new Invalid("Choose the Tasks to create", "tasks");
        }
        TrackerContext ctx = load(epic);
        WorkStructure st = ctx.settings.structureOf(epic);
        List<TemplateTask> current = tasks(epic, ankenPrefix).tasks();
        List<NewTicket> out = new ArrayList<>();
        for (int n = 0; n < in.size(); n++) {
            NewTaskIn t = in.get(n);
            String at = "tasks." + n + ".";
            TemplateTask tt = current.stream().filter(x -> x.match().equals(t.match())).findFirst()
                    .orElseThrow(() -> new Invalid(t.match() + " is not a Task of the anken's template", at + "match"));
            if (tt.existingKey() != null) {
                throw new Invalid(t.match() + " is already in the epic as " + tt.existingKey(), at + "match");
            }
            TaskTemplate tpl = st.tasks().stream().filter(x -> x.match().equals(t.match())).findFirst().orElseThrow();
            String name = Text.orElse(t.name(), "").strip();
            if (!tpl.equals(st.taskTemplate(name))) {
                throw new Invalid("\"" + name + "\" would not be recognised as the Task " + tpl.match() + ": keep the template's pattern",
                        at + "name");
            }
            out.add(new NewTicket("task:" + n, false, null, null, name, assigneeId, null, null, null, null));
        }
        return out;
    }

    /** The review of the Tasks to create, with the requests. */
    public BatchPreview previewTasks(Viewer actor, String epic, String ankenPrefix, String assigneeId, List<NewTaskIn> in) {
        require(epic);
        return tickets.previewBatch(new AnkenScope(Set.of(epic)), epic, List.of(), newTasks(epic, ankenPrefix, assigneeId, in));
    }

    /** Creates the Tasks in Jira and keeps the anken prefix. */
    public BatchResult createTasks(Viewer actor, String epic, String ankenPrefix, String assigneeId, List<NewTaskIn> in) {
        require(epic);
        List<NewTicket> creates = newTasks(epic, ankenPrefix, assigneeId, in);
        save(actor, epic, 2, ankenPrefix);
        return tickets.applyBatch(actor, new AnkenScope(Set.of(epic)), epic, List.of(), creates);
    }

    // --- step 4: members ---------------------------------------------------------------------------------------------------

    /** Every person, members of the anken first, with their hours a day on it today. */
    public List<MemberHours> members(String epic) {
        TrackerContext ctx = load(epic);
        Allocations a = new Allocations(allocationRows.findAll());
        Set<String> in = Set.copyOf(ankenMembers.findByEpic(epic).stream().map(tracker.domain.AnkenMember::memberId).toList());
        return members.findAll().stream()
                .sorted(Comparator.comparing((Member m) -> !in.contains(m.id())).thenComparing(Member::name, String.CASE_INSENSITIVE_ORDER))
                .map(m -> new MemberHours(m.id(), m.name(), m.role() == null ? null : m.role().value(), Objects.requireNonNullElse(m.country(), ""),
                        in.contains(m.id()), a.hoursOn(m.id(), epic, ctx.today)))
                .toList();
    }

    /**
     * Adds and removes members, and writes the hours that changed as allocations from today; nothing is kept when a check fails
     * (a member's hours over all ankens above the working day).
     */
    @Transactional
    public List<MemberHours> saveMembers(Viewer actor, String epic, List<MemberIn> in) {
        require(epic);
        TrackerContext ctx = load(epic);
        Allocations a = new Allocations(allocationRows.findAll());
        for (int n = 0; n < in.size(); n++) {
            MemberIn m = in.get(n);
            String at = "members." + n + ".";
            members.findById(m.memberId()).orElseThrow(() -> new Invalid("Member " + m.memberId() + " was not found", at + "member_id"));
            boolean member = ankenMembers.exists(epic, m.memberId());
            if (m.inAnken() && !member) {
                membersService.add(actor, epic, m.memberId());
            } else if (!m.inAnken() && member) {
                membersService.remove(actor, epic, m.memberId());
            }
            Double now = a.hoursOn(m.memberId(), epic, ctx.today);
            if (m.inAnken() && m.hoursPerDay() != null && !m.hoursPerDay().equals(now)) {
                try {
                    allocations.set(actor, m.memberId(), new AllocationChange(ctx.today, Map.of(epic, m.hoursPerDay())));
                } catch (Invalid e) {
                    throw new Invalid(e.getMessage(), at + "hours_per_day");
                }
            }
        }
        return members(epic);
    }
}
