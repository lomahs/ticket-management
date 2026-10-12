package tracker.adapters.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.Deps;
import tracker.application.usecases.AnkenUseCases;
import tracker.application.usecases.AnkenUseCases.AnkenCard;
import tracker.application.usecases.AnkenUseCases.AnkenDetail;
import tracker.application.usecases.AnkenUseCases.FunctionDraft;
import tracker.application.usecases.AnkenUseCases.FunctionPreview;
import tracker.application.usecases.AnkenUseCases.MatrixView;

@RestController
@RequestMapping("/api/v1/anken")
public class AnkenApi {
    private final Deps d;

    public AnkenApi(Deps d) {
        this.d = d;
    }

    public record KeysOut(List<String> keys) {
    }

    @GetMapping("")
    public List<AnkenCard> overview() {
        return AnkenUseCases.overview(d);
    }

    @GetMapping("/{epic}")
    public AnkenDetail detail(@PathVariable String epic) {
        return AnkenUseCases.detail(d, epic);
    }

    @GetMapping("/{epic}/matrix")
    public MatrixView matrix(@PathVariable String epic) {
        return AnkenUseCases.matrix(d, epic);
    }

    /** FunctionDraftIn (and FunctionCreateIn when {@code create}: a required, non-empty name and an optional function id). */
    private record Draft(FunctionDraft draft, String functionId) {
    }

    private static Draft draft(byte[] raw, boolean create) {
        Input in = new Input();
        Input.Obj b = in.body(raw);
        if (b == null) {
            in.check();
        }
        String parentKey = b.str("parent_key");
        String name;
        if (create) {
            name = b.str("name");
            in.minLength(b.at("name"), name, 1);
        } else {
            name = b.str("name", "");
        }
        String ba = b.str("ba");
        String bal = b.str("bal");
        String jm = b.str("jm");
        Double hours = b.number("create_hours");
        in.gt(b.at("create_hours"), hours, 0);
        in.le(b.at("create_hours"), hours, 1000);
        List<Map<String, String>> overrides = b.list("overrides", new ArrayList<>(Collections.nCopies(5, null)), (at, v) -> {
            if (v.isNull()) {
                return null;
            }
            Input.Obj o = in.model(v, at);
            if (o == null) {
                return null;
            }
            Map<String, String> m = new LinkedHashMap<>();
            String start = in.pattern(o.at("start"), o.nullableStr("start"), Input.ISO_DATE);
            String end = in.pattern(o.at("end"), o.nullableStr("end"), Input.ISO_DATE);
            if (start != null) {
                m.put("start", start);
            }
            if (end != null) {
                m.put("end", end);
            }
            return m;
        });
        in.maxItems(b.at("overrides"), overrides, 5);
        String functionId = null;
        if (create) {
            functionId = in.pattern(b.at("function_id"), b.nullableStr("function_id"), "^[A-Za-z]+\\d+$");
        }
        in.check();
        return new Draft(new FunctionDraft(parentKey, name, ba, bal, jm, hours, overrides), functionId);
    }

    @PostMapping("/{epic}/functions/preview")
    public FunctionPreview previewFunction(@PathVariable String epic, @RequestBody(required = false) byte[] raw) {
        return AnkenUseCases.previewFunction(d, epic, draft(raw, false).draft());
    }

    @PostMapping("/{epic}/functions")
    @ResponseStatus(HttpStatus.CREATED)
    public KeysOut createFunction(@PathVariable String epic, @RequestBody(required = false) byte[] raw) {
        Draft b = draft(raw, true);
        return new KeysOut(AnkenUseCases.createFunction(d, epic, b.draft(), b.functionId()));
    }
}
