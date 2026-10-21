package tracker.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.SeedRepository;
import tracker.application.settings.SettingsStore;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.Worklog;

/** Fills the anken memberships once from who already worked on each epic's sub-tasks. */
@Service
public class AnkenMemberSeeder {
    static final String SEED = "anken_members_seed";

    private final TrackerContextLoader loader;
    private final SettingsStore settings;
    private final AnkenMemberRepository ankenMembers;
    private final SeedRepository seeds;
    private final Clock clock;

    public AnkenMemberSeeder(TrackerContextLoader loader, SettingsStore settings, AnkenMemberRepository ankenMembers,
                             SeedRepository seeds, Clock clock) {
        this.loader = loader;
        this.settings = settings;
        this.ankenMembers = ankenMembers;
        this.seeds = seeds;
        this.clock = clock;
    }

    /** Returns the rows added; does nothing once the seed is marked done, which also happens when no epic is tracked yet. */
    @Transactional
    public int seedOnce() {
        if (seeds.done(SEED)) {
            return 0;
        }
        String now = Instant.now(clock).toString();
        TrackerContext ctx = loader.load(new AnkenScope(Set.copyOf(settings.load().jira.epics)));
        int added = 0;
        for (Anken a : ctx.anken) {
            for (String memberId : activeMembers(ctx, a.key())) {
                if (ctx.members.containsKey(memberId) && !ankenMembers.exists(a.key(), memberId)) {
                    ankenMembers.add(a.key(), memberId, "seed", now);
                    added++;
                }
            }
        }
        seeds.markDone(SEED, now);
        return added;
    }

    private static Set<String> activeMembers(TrackerContext ctx, String epic) {
        Set<String> out = new LinkedHashSet<>();
        for (Issue i : ctx.subtasks()) {
            if (!epic.equals(i.epicKey())) {
                continue;
            }
            out.add(i.assigneeId());
            out.add(ctx.worker(i));
            for (Worklog w : i.worklogs()) {
                out.add(w.memberId());
            }
        }
        for (Function fn : ctx.functions) {
            if (epic.equals(fn.epicKey())) {
                out.add(fn.ownerId());
                out.add(fn.reviewerId());
            }
        }
        out.remove(null);
        return out;
    }
}
