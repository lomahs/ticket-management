package tracker.adapters.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Open;
import tracker.adapters.web.auth.Open.Rule;
import tracker.application.service.OnboardingService;
import tracker.application.service.OnboardingService.EpicLookup;
import tracker.application.service.OnboardingService.MemberHours;
import tracker.application.service.OnboardingService.MemberIn;
import tracker.application.service.OnboardingService.NewTaskIn;
import tracker.application.service.OnboardingService.OnboardingView;
import tracker.application.service.OnboardingService.TemplateTasksView;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.BatchResult;
import tracker.domain.Viewer;

/** Onboarding a new anken: the PIM's (admins') step-by-step set-up, from its epic to its detail plan. */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingApi {
    private final OnboardingService onboarding;

    public OnboardingApi(OnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    /** The ankens being onboarded, and those finished, most recently saved first. */
    @Open(Rule.ADMIN)
    @GetMapping
    public List<OnboardingView> list() {
        return onboarding.list();
    }

    /** The epic as Jira has it; nothing is saved. */
    @Open(Rule.ADMIN)
    @GetMapping("/lookup")
    public EpicLookup lookup(@RequestParam("key") String key) {
        return onboarding.lookup(key);
    }

    public record StartIn(@NotBlank String key) {
    }

    /** Tracks the epic (a full sync) when needed and starts its onboarding; one being onboarded already resumes. */
    @Open(Rule.ADMIN)
    @PostMapping
    public OnboardingView start(@RequestBody @Valid StartIn body, Viewer viewer) {
        return onboarding.start(viewer, body.key());
    }

    @Open(Rule.ADMIN)
    @GetMapping("/{epic}")
    public OnboardingView get(@PathVariable("epic") String epic) {
        return onboarding.get(epic);
    }

    /** @param ankenPrefix null keeps the saved one */
    public record ProgressIn(int step, @Nullable String ankenPrefix) {
    }

    /** Records how far the onboarding got: the furthest of the saved step and this one. */
    @Open(Rule.ADMIN)
    @PutMapping("/{epic}")
    public OnboardingView save(@PathVariable("epic") String epic, @RequestBody @Valid ProgressIn body, Viewer viewer) {
        return onboarding.save(viewer, epic, body.step(), body.ankenPrefix());
    }

    /** Stops the onboarding; the epic stays tracked and what is in Jira stays. */
    @Open(Rule.ADMIN)
    @DeleteMapping("/{epic}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable("epic") String epic) {
        onboarding.discard(epic);
    }

    /** Marks the onboarding finished; refused while estimation rows are not in Jira. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/finish")
    public OnboardingView finish(@PathVariable("epic") String epic, Viewer viewer) {
        return onboarding.finish(viewer, epic);
    }

    /** The anken's template Tasks: those in the epic, and a proposed name for each missing one. */
    @Open(Rule.ADMIN)
    @GetMapping("/{epic}/tasks")
    public TemplateTasksView tasks(@PathVariable("epic") String epic, @RequestParam(name = "anken_prefix", required = false) @Nullable String prefix) {
        return onboarding.tasks(epic, prefix);
    }

    public record NewTaskBody(@NotNull String match, @NotNull String name) {
    }

    public record NewTasksIn(@NotNull String ankenPrefix, @NotBlank String assigneeId, @NotNull List<@NotNull @Valid NewTaskBody> tasks) {
        List<NewTaskIn> in() {
            return tasks.stream().map(t -> new NewTaskIn(t.match(), t.name())).toList();
        }
    }

    /** The review of the Tasks to create, with the requests; nothing is sent. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/tasks/preview")
    public BatchPreview previewTasks(@PathVariable("epic") String epic, @RequestBody @Valid NewTasksIn body, Viewer viewer) {
        return onboarding.previewTasks(viewer, epic, body.ankenPrefix(), body.assigneeId(), body.in());
    }

    /** Creates the missing template Tasks in Jira and keeps the anken prefix. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/tasks")
    public BatchResult createTasks(@PathVariable("epic") String epic, @RequestBody @Valid NewTasksIn body, Viewer viewer) {
        return onboarding.createTasks(viewer, epic, body.ankenPrefix(), body.assigneeId(), body.in());
    }

    /** Every person, the anken's members first, with their hours a day on it. */
    @Open(Rule.ADMIN)
    @GetMapping("/{epic}/members")
    public List<MemberHours> members(@PathVariable("epic") String epic) {
        return onboarding.members(epic);
    }

    public record MemberBody(@NotBlank String memberId, boolean inAnken, @Nullable Double hoursPerDay) {
    }

    public record MembersIn(@NotNull List<@NotNull @Valid MemberBody> members) {
    }

    /** Adds and removes members and writes the changed hours as allocations from today. */
    @Open(Rule.ADMIN)
    @PutMapping("/{epic}/members")
    public List<MemberHours> saveMembers(@PathVariable("epic") String epic, @RequestBody @Valid MembersIn body, Viewer viewer) {
        return onboarding.saveMembers(viewer, epic, body.members().stream().map(m -> new MemberIn(m.memberId(), m.inAnken(), m.hoursPerDay())).toList());
    }
}
