package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.gui.DrawNApplication;
import drawn.model.event.Event;
import drawn.model.ticket.TicketType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single-stage customer shell with replaceable center content. */
public final class MainShell {
    private final DrawNApplication app;
    private final AppServices services;
    private final AppSession session;
    private final BorderPane root = new BorderPane();
    private final VBox sidebar = new VBox(8);
    private final Map<String, Button> navigation = new LinkedHashMap<>();
    private boolean collapsed;

    public MainShell(DrawNApplication app, AppServices services, AppSession session) { this.app=app;this.services=services;this.session=session; }

    public Parent view() {
        root.getStyleClass().add("app-root");
        root.setLeft(sidebar);
        sidebar.getStyleClass().add("sidebar");
        showPage("Home");
        rebuildSidebar();
        return root;
    }

    private void rebuildSidebar() {
        sidebar.getChildren().clear(); sidebar.setPrefWidth(collapsed ? 72 : 250); sidebar.setMinWidth(collapsed ? 72 : 250);
        HBox header = new HBox(); header.setAlignment(Pos.CENTER_LEFT);
        Label brand = new Label("DrawN"); brand.getStyleClass().add("sidebar-brand"); brand.setVisible(!collapsed); brand.setManaged(!collapsed);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Button toggle = new Button(collapsed ? "☰" : "‹"); toggle.getStyleClass().add("icon-button"); toggle.setTooltip(new Tooltip(collapsed ? "Expand sidebar" : "Collapse sidebar"));
        toggle.setOnAction(e -> { collapsed=!collapsed; rebuildSidebar(); }); header.getChildren().addAll(brand,spacer,toggle); sidebar.getChildren().add(header);
        addNav("Home", "⌂"); addNav("Explore Events", "⌕"); addNav("My Events", "★"); addNav("Venue Reservations", "⌂"); addNav("My Tickets", "▣"); addNav("Wallet & Transactions", "₱");
        Region grow = new Region(); VBox.setVgrow(grow, Priority.ALWAYS); sidebar.getChildren().add(grow);
        Button profile = new Button(collapsed ? initials() : initials()+"  "+session.customer().getName()+"\n"+identifier());
        profile.getStyleClass().add("profile-section"); profile.setMaxWidth(Double.MAX_VALUE); profile.setAlignment(Pos.CENTER_LEFT); profile.setTooltip(new Tooltip(session.customer().getName()));
        ContextMenu menu = new ContextMenu(); MenuItem profileItem=new MenuItem("My Profile"); profileItem.setOnAction(e->showPage("My Profile")); MenuItem logout=new MenuItem("Log Out"); logout.setOnAction(e->{session.clear();app.showLogin();}); menu.getItems().addAll(profileItem,logout);
        profile.setOnAction(e -> menu.show(profile, javafx.geometry.Side.TOP, 0, 0)); sidebar.getChildren().add(profile);
    }
    private void addNav(String name,String icon) {
        Button button=new Button(collapsed ? icon : icon+"  "+name); button.setMaxWidth(Double.MAX_VALUE); button.setAlignment(Pos.CENTER_LEFT);button.getStyleClass().add("sidebar-item");button.setTooltip(new Tooltip(name));button.setOnAction(e->showPage(name));navigation.put(name,button);sidebar.getChildren().add(button);
    }
    private void showPage(String name) {
        navigation.values().forEach(button -> button.getStyleClass().remove("sidebar-item-active"));
        Button active=navigation.get(name);if(active!=null)active.getStyleClass().add("sidebar-item-active");
        root.setCenter(name.equals("Home") ? home() : placeholder(name));
    }
    private Node home() {
        Label greeting=new Label("Welcome back, "+session.account().getFirstName()+"!");greeting.getStyleClass().add("page-title");
        Label message=new Label("Discover what's happening around you.");message.getStyleClass().add("secondary-text");
        Label balance=new Label("Wallet balance: PHP "+money(session.account().getWalletBalance()));balance.getStyleClass().add("balance-label");
        HBox header=new HBox(12,new VBox(4,greeting,message),new Region(),balance);HboxGrow(header);header.getStyleClass().add("page-header");
        Node feed;
        try { List<Event> events=services.publishing().publishedEvents(); feed=events.isEmpty()?emptyState():eventGrid(events); }
        catch(Exception ex){ feed=errorState("Published events could not be loaded: "+ex.getMessage()); }
        Button create=new Button("+");create.getStyleClass().add("floating-action-button");create.setTooltip(new Tooltip("Create Event"));create.setOnAction(e->showPage("Create Event"));
        StackPane content=new StackPane(feed,create);StackPane.setAlignment(create,Pos.BOTTOM_RIGHT);StackPane.setMargin(create,new Insets(0,28,28,0));
        BorderPane page=new BorderPane(content);page.setTop(header);page.getStyleClass().add("content-page");return page;
    }
    private Node eventGrid(List<Event> events) {
        GridPane grid=new GridPane();grid.setHgap(16);grid.setVgap(16);grid.setPadding(new Insets(4,0,84,0));
        ScrollPane scroll=new ScrollPane(grid);scroll.setFitToWidth(true);scroll.getStyleClass().add("content-scroll");
        scroll.viewportBoundsProperty().addListener((o,old,bounds)->populateGrid(grid,events,bounds.getWidth()));populateGrid(grid,events,900);return scroll;
    }
    private void populateGrid(GridPane grid,List<Event> events,double width) {
        int columns=width>=1050?3:width>=690?2:1;grid.getChildren().clear();grid.getColumnConstraints().clear();for(int i=0;i<columns;i++){ColumnConstraints c=new ColumnConstraints();c.setPercentWidth(100.0/columns);c.setHgrow(Priority.ALWAYS);grid.getColumnConstraints().add(c);}for(int i=0;i<events.size();i++)grid.add(eventCard(events.get(i)),i%columns,i/columns);
    }
    private Node eventCard(Event event) {
        Label title=new Label(event.name());title.getStyleClass().add("event-card-title");title.setWrapText(true);
        String description=event.description().length()>130?event.description().substring(0,127)+"…":event.description();Label text=new Label(description);text.setWrapText(true);text.getStyleClass().add("secondary-text");
        Label details=new Label(event.type()+" • "+event.venue().name()+"\n"+event.date()+" • "+event.startTime()+"\n"+ticketSummary(event));details.setWrapText(true);details.getStyleClass().add("secondary-text");
        Button view=new Button("View Details");view.getStyleClass().add("secondary-button");view.setOnAction(e->new EventDetailsView(event,services).show());
        VBox card=new VBox(12,title,details,text,new Region(),view);VBox.setVgrow(card.getChildren().get(3),Priority.ALWAYS);card.getStyleClass().add("event-card");card.setMaxWidth(Double.MAX_VALUE);return card;
    }
    private Node emptyState(){Button create=new Button("Create New Event");create.getStyleClass().add("primary-button");create.setOnAction(e->showPage("Create Event"));VBox box=new VBox(12,new Label("✦"),title("No published events yet."),subtitle("Be the first to bring an event to life with DrawN."),create);box.setAlignment(Pos.CENTER);box.getStyleClass().add("empty-state");return box;}
    private Node placeholder(String page){return new VBox(12,title(page),subtitle(page.equals("Create Event")?"Create Event is pending GUI migration. Use the console workflow to preserve approved-reservation and confirmation requirements.":"This customer workflow is pending GUI migration. No data or payment will be changed here."));}
    private Node errorState(String message){return new VBox(12,title("Unable to load events"),subtitle(message));}
    private String ticketSummary(Event event){try{List<TicketType> types=services.publishing().ticketTypes(event.id());if(types.isEmpty())return "Free event";BigDecimal low=types.stream().map(TicketType::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);int remaining=types.stream().mapToInt(TicketType::remaining).sum();return "From PHP "+money(low)+" • "+remaining+" tickets available";}catch(Exception ignored){return "Ticket information unavailable";}}
    private static void HboxGrow(HBox box){for(Node node:box.getChildren())if(node instanceof Region region)HBox.setHgrow(region,Priority.ALWAYS);}
    private String initials(){String[] parts=session.customer().getName().trim().split("\\s+");return (parts[0].substring(0,1)+(parts.length>1?parts[parts.length-1].substring(0,1):"")).toUpperCase();}
    private String identifier(){return session.customer().getEmail()!=null?session.customer().getEmail():session.customer().getPhoneNumber();}
    private static String money(BigDecimal amount){return amount.setScale(2).toPlainString();}
    private static Label title(String text){Label label=new Label(text);label.getStyleClass().add("page-title");return label;}private static Label subtitle(String text){Label label=new Label(text);label.getStyleClass().add("secondary-text");label.setWrapText(true);return label;}
}
