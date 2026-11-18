package drawn.gui.views;

import drawn.gui.AppSession;
import drawn.gui.AppServices;
import drawn.model.event.Event;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.TicketCategory;
import drawn.model.ticket.TicketType;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Organizer edit form that deliberately exposes no venue or schedule controls. */
final class EditEventDialog {
    private final AppServices services;
    private final AppSession session;
    private final Event event;
    private final Runnable refreshed;
    private final VBox ticketRows = new VBox(8);
    private final List<TicketRow> rows = new ArrayList<>();

    EditEventDialog(AppServices services, AppSession session, Event event, Runnable refreshed) {
        this.services = services; this.session = session; this.event = event; this.refreshed = refreshed;
    }

    void show() {
        try {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Edit published event");
            dialog.setHeaderText("Update event details and ticket setup. Venue, date, and start time stay fixed.");
            ButtonType save = new ButtonType("Save changes", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

            TextField name = Ui.field("Event name"); name.setText(event.name());
            TextArea description = new TextArea(event.description()); description.setWrapText(true); description.setPrefRowCount(3);
            ComboBox<EventType> type = new ComboBox<>(FXCollections.observableArrayList(EventType.values())); type.setValue(event.type());
            TextField attendance = Ui.field("Expected attendance"); attendance.setText(Integer.toString(event.expectedAttendance()));
            CheckBox privateEvent = new CheckBox("Private event"); privateEvent.setSelected(event.visibility() == EventVisibility.PRIVATE);
            TextField keyword = Ui.field("Private event access keyword");
            keyword.setText(services.privateEventAccess().keywordFor(event.id()));
            keyword.setDisable(!privateEvent.isSelected());
            privateEvent.selectedProperty().addListener((o, was, is) -> keyword.setDisable(!is));
            GridPane form = Ui.form();
            Ui.row(form, "Event name", name); Ui.row(form, "Description", description); Ui.row(form, "Event type", type);
            Ui.row(form, "Expected attendance", attendance); Ui.row(form, "Visibility", privateEvent); Ui.row(form, "Access keyword", keyword);
            Ui.row(form, "Fixed schedule", new Label(event.venue().name() + " • " + event.date() + " • " + event.startTime() + "–" + event.endTime()));
            VBox content = new VBox(12,
                    new HBox(8, BackNavigation.button(dialog::close), Ui.muted("My Events  ›  Edit Event")), form);
            if (event.purpose() == EventPurpose.TICKETED_EVENT) {
                List<TicketType> prior = services.publishing().ticketTypes(event.id());
                for (TicketType ticket : prior) addRow(ticket);
                var add = Ui.secondary("Add ticket category", () -> addRow(null));
                content.getChildren().addAll(Ui.section("Ticket setup"), Ui.muted("Use YYYY-MM-DD HH:mm for each sales window. Sold tickets remain protected."), ticketRows, add);
            }
            content.setPadding(new Insets(4));
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().setPrefWidth(850);
            boolean[] saved = {false};
            dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, action -> {
                action.consume();
                if (save(name, description, type, attendance, privateEvent, keyword)) {
                    saved[0] = true;
                    dialog.close();
                }
            });
            dialog.showAndWait();
            if (saved[0]) refreshed.run();
        } catch (Exception ex) { Ui.error("Could not open event editor", Ui.message(ex)); }
    }

    private void addRow(TicketType original) {
        TicketRow row = new TicketRow(original);
        rows.add(row);
        ticketRows.getChildren().add(row.view());
    }

    private boolean save(TextField name, TextArea description, ComboBox<EventType> type, TextField attendance,
                         CheckBox privateEvent, TextField keyword) {
        try {
            String eventName = Ui.required(name.getText(), "Event name");
            String text = Ui.required(description.getText(), "Event description");
            int people = Ui.whole(attendance.getText(), 1, event.venue().capacity(), "Expected attendance");
            EventVisibility visibility = privateEvent.isSelected() ? EventVisibility.PRIVATE : EventVisibility.PUBLIC;
            String access = visibility == EventVisibility.PRIVATE ? Ui.required(keyword.getText(), "Private event access keyword") : "";
            List<TicketType> ticketTypes = new ArrayList<>();
            List<TicketCategory> categories = new ArrayList<>();
            if (event.purpose() == EventPurpose.TICKETED_EVENT) {
                if (rows.isEmpty()) throw new IllegalArgumentException("Add at least one ticket category.");
                for (TicketRow row : rows) {
                    TicketType ticket = row.toTicket(); ticketTypes.add(ticket);
                    categories.add(new TicketCategory(ticket.name(), ticket.price(), ticket.quantity(), ticket.salesStart(), ticket.salesDeadline()));
                }
                int total = ticketTypes.stream().mapToInt(TicketType::quantity).sum();
                if (total > event.venue().capacity()) throw new IllegalArgumentException("Total ticket quantity exceeds venue capacity.");
            }
            EventDraft draft = new EventDraft(eventName, text, type.getValue(), event.purpose(), visibility, event.date(),
                    event.startTime(), event.endTime(), people, event.venue(), categories);
            services.workflow().editPublishedEvent(session.customer(), event.id(), draft, ticketTypes, access);
            Ui.info("Event updated", "Your event changes have been saved.");
            return true;
        } catch (Exception ex) {
            Ui.error("Could not save event changes", Ui.message(ex));
            return false;
        }
    }

    private final class TicketRow {
        private final TicketType original;
        private final TextField name = Ui.field("Category");
        private final TextField price = Ui.field("Price ₱");
        private final TextField quantity = Ui.field("Quantity");
        private final TextField open = Ui.field("Sales open YYYY-MM-DD HH:mm");
        private final TextField close = Ui.field("Sales close YYYY-MM-DD HH:mm");
        private final HBox box = new HBox(6);

        TicketRow(TicketType original) {
            this.original = original;
            if (original != null) {
                name.setText(original.name()); price.setText(original.price().toPlainString()); quantity.setText(Integer.toString(original.quantity()));
                if (original.salesStart() != null) open.setText(format(original.salesStart()));
                if (original.salesDeadline() != null) close.setText(format(original.salesDeadline()));
            }
        }

        HBox view() {
            box.getChildren().setAll(name, price, quantity, open, close);
            if (original != null && original.sold() > 0) box.getChildren().add(new Label(original.sold() + " sold"));
            else {
                var remove = Ui.danger("Remove", () -> { ticketRows.getChildren().remove(box); rows.remove(this); });
                box.getChildren().add(remove);
            }
            return box;
        }

        TicketType toTicket() {
            String ticketName = Ui.required(name.getText(), "Ticket category name");
            BigDecimal amount = Ui.amount(price.getText(), "Ticket price");
            if (amount.signum() <= 0) throw new IllegalArgumentException("Paid ticket prices must be greater than zero.");
            int count = Ui.whole(quantity.getText(), original == null ? 1 : original.sold(), Integer.MAX_VALUE, "Ticket quantity");
            LocalDateTime opens = parseOptional(open.getText(), "Sales opening time");
            LocalDateTime closes = parseOptional(close.getText(), "Sales closing time");
            if ((opens == null) != (closes == null)) throw new IllegalArgumentException("Enter both sales opening and closing times.");
            if (opens == null && original == null) throw new IllegalArgumentException("New ticket categories need a sales opening and closing time.");
            String id = original == null ? UUID.randomUUID().toString() : original.id();
            int sold = original == null ? 0 : original.sold();
            String detail = original == null ? ticketName + " ticket" : original.description();
            return new TicketType(id, event.id(), ticketName, detail, amount, count, sold, opens, closes);
        }

        private static LocalDateTime parseOptional(String text, String label) {
            if (text.isBlank()) return null;
            try { return LocalDateTime.parse(text.trim().replace(' ', 'T')); }
            catch (DateTimeParseException ex) { throw new IllegalArgumentException(label + ": enter YYYY-MM-DD HH:mm."); }
        }
        private static String format(LocalDateTime time) { return time.toString().replace('T', ' '); }
    }
}
