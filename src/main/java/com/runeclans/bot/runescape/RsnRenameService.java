package com.runeclans.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

/**
 * The bot's own rename history ({@code younglings.rsn_rename_candidate}), kept for the record and the site's "recent renames" lists.
 * <p>
 * The bot no longer spots renames itself (T-1160): the RuneClans hub spots and links name changes for every app, and {@link HubNameChanges}
 * follows them (adding a confirmed row here for each server). Candidates the bot raised before are kept as they are; no new ones are made.
 */
@BService
public class RsnRenameService {
    private final RsnRenameRepository renameRepository;

    public RsnRenameService(RsnRenameRepository renameRepository) {
        this.renameRepository = renameRepository;
    }

    public RsnRenameRepository.RenameCandidate getCandidate(long candidateId) {
        return renameRepository.getById(candidateId);
    }

    /** Records the audit trail for an admin's manual "Update RSN" action — the actual link update happens at the call site. */
    public void recordManualRename(long guildId, String oldRsn, String newRsn) {
        long id = renameRepository.create(guildId, oldRsn, newRsn, "MANUAL", "Manually updated by an admin via Player Lookup.");
        renameRepository.resolve(id, "CONFIRMED");
    }
}
