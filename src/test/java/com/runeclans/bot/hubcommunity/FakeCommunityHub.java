package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A scripted hub for the community tests: answers by "METHOD path" (longest matching prefix wins) and records every call. */
final class FakeCommunityHub implements HubCommunityClient.Hub {
    record Call(String method, String path, String body, Map<String, String> headers, boolean queued, String key) {}

    final List<Call> calls = new ArrayList<>();
    private final Map<String, RuneClansHubClient.Answer> answers = new LinkedHashMap<>();
    /** When set, member writes are held (the hub write queue's QUEUED). */
    boolean holdWrites;

    FakeCommunityHub answer(String methodAndPath, int status, DataObject json) {
        answers.put(methodAndPath, new RuneClansHubClient.Answer(status, json, null));
        return this;
    }

    FakeCommunityHub ok(String methodAndPath, DataObject json) {
        return answer(methodAndPath, 200, json);
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
        calls.add(new Call(method, path, body, headers, false, null));
        return pick(method, path);
    }

    @Override
    public HubWriteQueue.Result write(String method, String path, String body, String supersedeKey) {
        calls.add(new Call(method, path, body, Map.of(), holdWrites, supersedeKey));
        if (holdWrites) return new HubWriteQueue.Result(HubWriteQueue.Outcome.QUEUED, null);
        RuneClansHubClient.Answer a = pick(method, path);
        return new HubWriteQueue.Result(a.ok() ? HubWriteQueue.Outcome.SENT : HubWriteQueue.Outcome.REFUSED, a);
    }

    List<Call> calls(String method, String pathPrefix) {
        return calls.stream().filter(c -> c.method().equals(method) && c.path().startsWith(pathPrefix)).toList();
    }
}
