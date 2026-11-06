package com.runeclans.bot.hubevents;

import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A scripted hub for the event tests: answers by "METHOD path" (longest matching prefix wins) and records every call. */
final class FakeEventHub implements HubEventClient.Hub {
    record Call(String method, String path, String body, Map<String, String> headers) {
        DataObject json() {
            return body == null ? DataObject.empty() : DataObject.fromJson(body);
        }
    }

    final List<Call> calls = new ArrayList<>();
    private final Map<String, RuneClansHubClient.Answer> answers = new LinkedHashMap<>();
    byte[] png;

    FakeEventHub answer(String methodAndPath, int status, DataObject json) {
        answers.put(methodAndPath, new RuneClansHubClient.Answer(status, json, null));
        return this;
    }

    FakeEventHub ok(String methodAndPath, DataObject json) {
        return answer(methodAndPath, 200, json);
    }

    /** The hub can't be reached for this path (the transport's null). */
    FakeEventHub down(String methodAndPath) {
        answers.put(methodAndPath, null);
        return this;
    }

    private RuneClansHubClient.Answer pick(String method, String path) {
        String key = method + " " + path;
        String best = null;
        for (String k : answers.keySet()) {
            if (key.startsWith(k) && (best == null || k.length() > best.length())) best = k;
        }
        return best == null ? new RuneClansHubClient.Answer(404, DataObject.empty().put("error", "not_found").put("message", "Not here."), null) : answers.get(best);
    }

    @Override
    public RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers) {
        calls.add(new Call(method, path, body, headers));
        return pick(method, path);
    }

    @Override
    public byte[] bytes(String path, Map<String, String> headers) {
        calls.add(new Call("BYTES", path, null, headers));
        return png;
    }

    List<Call> calls(String method, String pathPart) {
        return calls.stream().filter(c -> c.method().equals(method) && c.path().contains(pathPart)).toList();
    }

    Call last() {
        return calls.get(calls.size() - 1);
    }
}
