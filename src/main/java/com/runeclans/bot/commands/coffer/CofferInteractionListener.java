package com.runeclans.bot.commands.coffer;

import com.runeclans.bot.coffer.CofferRepository;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles button interactions for the coffer transfer 2-part verification flow (only active when
 * CofferService.REQUIRE_TRANSFER_VERIFICATION is true), plus the {@code /coffer} hub's buttons and
 * modals (see {@link CofferHubPanel}).
 */
@BService
public class CofferInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(CofferInteractionListener.class);

    private static final String ACCEPT_PREFIX = "coffer_transfer_accept:";
    private static final String REJECT_PREFIX = "coffer_transfer_reject:";

    private final CofferRepository repository;
    private final CofferService cofferService;
    private final AdminRoleFilter adminRoleFilter;

    public CofferInteractionListener(CofferRepository repository, CofferService cofferService,
                                      AdminRoleFilter adminRoleFilter) {
        this.repository = repository;
        this.cofferService = cofferService;
        this.adminRoleFilter = adminRoleFilter;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();

        try {
            if (id.startsWith("coffer_hub_")) {
                handleHubButton(event, id);
                return;
            }

            if (!CofferService.REQUIRE_TRANSFER_VERIFICATION) return;

            if (id.startsWith(ACCEPT_PREFIX)) {
                handleAccept(event, id.substring(ACCEPT_PREFIX.length()));
            } else if (id.startsWith(REJECT_PREFIX)) {
                handleReject(event, id.substring(REJECT_PREFIX.length()));
            }
        } catch (Exception e) {
            log.error("Unhandled exception in coffer button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith("coffer_hub_")) return;

        try {
            handleHubModal(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in coffer modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    // --- /coffer hub: buttons ---

    private void handleHubButton(ButtonInteractionEvent event, String id) {
        if (id.startsWith("coffer_hub_display_page:")) {
            cofferService.displayCoffer(event, Integer.parseInt(id.split(":")[1]), true);
            return;
        }
        if (id.startsWith("coffer_hub_log_page:")) {
            cofferService.displayLog(event, Integer.parseInt(id.split(":")[1]), true);
            return;
        }

        switch (id) {
            case "coffer_hub_submit" -> event.replyModal(buildSubmitModal()).queue();
            case "coffer_hub_display" -> cofferService.displayCoffer(event, 0, false);
            case "coffer_hub_log" -> cofferService.displayLog(event, 0, false);

            case "coffer_hub_transfer" -> {
                if (!isAdmin(event)) {
                    replyNotAdmin(event);
                    return;
                }
                event.replyModal(buildTransferModal()).queue();
            }

            case "coffer_hub_giveaway" -> {
                if (!isAdmin(event)) {
                    replyNotAdmin(event);
                    return;
                }
                event.replyModal(buildGiveawayModal()).queue();
            }
        }
    }

    private boolean isAdmin(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        return guild != null && member != null && adminRoleFilter.isAuthorized(guild, member);
    }

    private void replyNotAdmin(ButtonInteractionEvent event) {
        Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.you_need_the_admin_role_or"));
    }

    // --- /coffer hub: modals ---

    private void handleHubModal(ModalInteractionEvent event, String id) {
        switch (id) {
            case "coffer_hub_submit_modal" -> {
                String donorName = event.getValue("coffer_hub_donor").getAsString().trim();
                String amount = event.getValue("coffer_hub_amount").getAsString().trim();
                cofferService.submitDonation(event, donorName, amount);
            }

            case "coffer_hub_transfer_modal" -> {
                if (!isAdmin(event)) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.you_need_the_admin_role_or"));
                    return;
                }

                User recipient = event.getValue("coffer_hub_transfer_recipient").getAsMentions().getUsers().getFirst();
                String amount = event.getValue("coffer_hub_amount").getAsString().trim();

                var fromMapping = event.getValue("coffer_hub_transfer_from");
                User from = (fromMapping != null && !fromMapping.getAsMentions().getUsers().isEmpty())
                        ? fromMapping.getAsMentions().getUsers().getFirst()
                        : null;

                cofferService.transferCoffer(event, from, recipient, amount);
            }

            case "coffer_hub_giveaway_modal" -> {
                if (!isAdmin(event)) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.you_need_the_admin_role_or"));
                    return;
                }

                User recipient = event.getValue("coffer_hub_giveaway_recipient").getAsMentions().getUsers().getFirst();
                String amount = event.getValue("coffer_hub_amount").getAsString().trim();

                var holderMapping = event.getValue("coffer_hub_giveaway_holder");
                User holder = (holderMapping != null && !holderMapping.getAsMentions().getUsers().isEmpty())
                        ? holderMapping.getAsMentions().getUsers().getFirst()
                        : null;

                var descriptionMapping = event.getValue("coffer_hub_giveaway_description");
                String description = descriptionMapping != null ? descriptionMapping.getAsString().trim() : null;

                cofferService.recordGiveaway(event, holder, recipient, amount, description);
            }
        }
    }

    private boolean isAdmin(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        return guild != null && member != null && adminRoleFilter.isAuthorized(guild, member);
    }

    private Modal buildSubmitModal() {
        TextInput donorInput = TextInput.create("coffer_hub_donor", TextInputStyle.SHORT)
                .setPlaceholder("In-game name of the player who donated")
                .setRequired(true)
                .setRequiredRange(1, 50)
                .build();
        TextInput amountInput = TextInput.create("coffer_hub_amount", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 150M, 500K, 1.5B, 150000, 150_000")
                .setRequired(true)
                .setRequiredRange(1, 20)
                .build();

        return Modal.create("coffer_hub_submit_modal", "Log a Donation")
                .addComponents(
                        Label.of("Donor name", donorInput),
                        Label.of("Amount", amountInput)
                )
                .build();
    }

    private Modal buildTransferModal() {
        EntitySelectMenu fromSelect = EntitySelectMenu.create(
                        "coffer_hub_transfer_from", EntitySelectMenu.SelectTarget.USER)
                .setRequired(false)
                .setRequiredRange(0, 1)
                .setPlaceholder("Holder transferring the GP (defaults to you)")
                .build();
        EntitySelectMenu recipientSelect = EntitySelectMenu.create(
                        "coffer_hub_transfer_recipient", EntitySelectMenu.SelectTarget.USER)
                .setRequiredRange(1, 1)
                .setPlaceholder("Coffer holder to transfer to")
                .build();
        TextInput amountInput = TextInput.create("coffer_hub_amount", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 500M, 1B")
                .setRequired(true)
                .setRequiredRange(1, 20)
                .build();

        return Modal.create("coffer_hub_transfer_modal", "Transfer Coffer GP")
                .addComponents(
                        Label.of("From (optional)", fromSelect),
                        Label.of("Recipient", recipientSelect),
                        Label.of("Amount", amountInput)
                )
                .build();
    }

    private Modal buildGiveawayModal() {
        EntitySelectMenu recipientSelect = EntitySelectMenu.create(
                        "coffer_hub_giveaway_recipient", EntitySelectMenu.SelectTarget.USER)
                .setRequiredRange(1, 1)
                .setPlaceholder("Discord user who won the prize")
                .build();
        TextInput amountInput = TextInput.create("coffer_hub_amount", TextInputStyle.SHORT)
                .setPlaceholder("e.g. 300M")
                .setRequired(true)
                .setRequiredRange(1, 20)
                .build();
        EntitySelectMenu holderSelect = EntitySelectMenu.create(
                        "coffer_hub_giveaway_holder", EntitySelectMenu.SelectTarget.USER)
                .setRequired(false)
                .setRequiredRange(0, 1)
                .setPlaceholder("Holder paying out the prize (defaults to you)")
                .build();
        TextInput descriptionInput = TextInput.create("coffer_hub_giveaway_description", TextInputStyle.SHORT)
                .setPlaceholder("What the prize was given for (optional)")
                .setRequired(false)
                .setRequiredRange(0, 100)
                .build();

        return Modal.create("coffer_hub_giveaway_modal", "Record a Giveaway")
                .addComponents(
                        Label.of("Recipient", recipientSelect),
                        Label.of("Amount", amountInput),
                        Label.of("Paid by (optional)", holderSelect),
                        Label.of("Reason (optional)", descriptionInput)
                )
                .build();
    }

    private void handleAccept(ButtonInteractionEvent event, String transferIdStr) {
        long transferId = parseTransferId(event, transferIdStr);
        if (transferId < 0) return;

        CofferTransfer transfer = repository.getPendingTransfer(transferId);
        if (transfer == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.this_transfer_no_longer_exists_or"));
            return;
        }
        if (transfer.toDiscordId() != event.getUser().getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.only_the_intended_recipient_can_accept"));
            return;
        }

        CofferRepository.AcceptResult accepted = repository.acceptTransfer(transferId);
        if (accepted == CofferRepository.AcceptResult.ALREADY_RESOLVED) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.this_transfer_no_longer_exists_or"));
            return;
        }
        if (accepted == CofferRepository.AcceptResult.INSUFFICIENT_BALANCE) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.the_sender_no_longer_holds_enough"));
            return;
        }
        Containers.reply(event, Containers.SUCCESS, false,
                Text.of(event, "coffer.transfer_of_accepted", GpAmountParser.format(transfer.amount())));
    }

    private void handleReject(ButtonInteractionEvent event, String transferIdStr) {
        long transferId = parseTransferId(event, transferIdStr);
        if (transferId < 0) return;

        CofferTransfer transfer = repository.getPendingTransfer(transferId);
        if (transfer == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.this_transfer_no_longer_exists_or"));
            return;
        }
        if (transfer.toDiscordId() != event.getUser().getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.only_the_intended_recipient_can_reject"));
            return;
        }

        if (!repository.rejectTransfer(transferId)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.this_transfer_no_longer_exists_or"));
            return;
        }
        Containers.reply(event, Containers.DANGER, false,
                Text.of(event, "coffer.transfer_of_rejected", GpAmountParser.format(transfer.amount())));
    }

    private long parseTransferId(ButtonInteractionEvent event, String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "coffer.invalid_transfer_id"));
            return -1;
        }
    }
}
