package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.gui.DrawNApplication;
import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.ticket.TicketType;
import drawn.service.payment.CommissionPolicy;
import drawn.service.payment.OrganizerEarnings;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single-stage customer shell: sidebar navigation with replaceable center content. */
public final class MainShell {
    static final String HOME = "Home";
    static final String EXPLORE = "Explore Events";
    static final String MY_EVENTS = "My Events";
    static final String CREATE_EVENT = "Create Event";
    static final String VENUES = "Venue Reservations";
    static final String REQUEST_VENUE = "Request Venue";
    static final String TICKETS = "My Tickets";
    static final String WALLET = "Wallet & Transactions";
    static final String PROFILE = "My Profile";

    private final DrawNApplication app;
    private final AppServices services;
    private final AppSession session;
    private final BorderPane root = new BorderPane();
    private final VBox sidebar = new VBox(8);
    private final Map<String, Button> navigation = new LinkedHashMap<>();
    private String current = HOME;
    private boolean collapsed;

    public MainShell(DrawNApplication app, AppServices services, AppSession session) { this.app=app;this.services=services;this.session=session; }

    public Parent view() {
        root.getStyleClass().add("app-root");
        root.setLeft(sidebar);
        sidebar.getStyleClass().add("sidebar");
        rebuildSidebar();
        showPage(HOME);
        return root;
    }

    AppServices services() { return services; }
    AppSession session() { return session; }
    String currentPage() { return current; }
    Node currentContent() { return root.getCenter(); }

    /** Rebuilds the named page from fresh service data. */
    public void showPage(String name) {
        current = name;
        navigation.values().forEach(button -> button.getStyleClass().remove("sidebar-item-active"));
        Button active = navigation.get(highlightFor(name));
        if (active != null) active.getStyleClass().add("sidebar-item-active");
        root.setCenter(switch (name) {
            case HOME -> home();
            case EXPLORE -> new ExploreEventsPage(this, null).view();
            case MY_EVENTS -> new MyEventsPage(this).view();
            case CREATE_EVENT -> new CreateEventPage(this).view();
            case VENUES -> new VenueReservationsPage(this).view();
            case REQUEST_VENUE -> new VenueRequestPage(this).view();
            case TICKETS -> new TicketsPage(this).view();
            case WALLET -> new WalletPage(this).view();
            case PROFILE -> new ProfilePage(this).view();
            default -> throw new IllegalArgumentException("Unknown page: " + name);
        });
    }

    /** Opens Explore Events with one event already selected. */
    void showEvent(Event event) {
        current = EXPLORE;
        navigation.values().forEach(button -> button.getStyleClass().remove("sidebar-item-active"));
        navigation.get(EXPLORE).getStyleClass().add("sidebar-item-active");
        root.setCenter(new ExploreEventsPage(this, event).view());
    }

    void logOut() { session.clear(); app.showLogin(); }

    private static String highlightFor(String page) {
        return switch (page) {
            case CREATE_EVENT -> MY_EVENTS;
            case REQUEST_VENUE -> VENUES;
            default -> page;
        };
    }

    private void rebuildSidebar() {
        sidebar.getChildren().clear(); navigation.clear(); sidebar.setPrefWidth(collapsed ? 72 : 250); sidebar.setMinWidth(collapsed ? 72 : 250);
        HBox header = new HBox(); header.setAlignment(Pos.CENTER_LEFT);
        Label brand = new Label("DrawN"); brand.getStyleClass().add("sidebar-brand"); brand.setVisible(!collapsed); brand.setManaged(!collapsed);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Button toggle = new Button(collapsed ? "☰" : "‹"); toggle.getStyleClass().add("icon-button"); toggle.setTooltip(new Tooltip(collapsed ? "Expand sidebar" : "Collapse sidebar"));
        toggle.setOnAction(e -> { collapsed=!collapsed; rebuildSidebar(); showPage(current); }); header.getChildren().addAll(brand,spacer,toggle); sidebar.getChildren().add(header);
        addNav(HOME, "⌂"); addNav(EXPLORE, "⌕"); addNav(MY_EVENTS, "★"); addNav(VENUES, "▤"); addNav(TICKETS, "▣"); addNav(WALLET, "₱");
        Region grow = new Region(); VBox.setVgrow(grow, Priority.ALWAYS); sidebar.getChildren().add(grow);
        Button profile = new Button(collapsed ? initials() : initials()+"  "+session.customer().getName()+"\n"+identifier());
        profile.getStyleClass().add("profile-section"); profile.setMaxWidth(Double.MAX_VALUE); profile.setAlignment(Pos.CENTER_LEFT); profile.setTooltip(new Tooltip(session.customer().getName()));
        ContextMenu menu = new ContextMenu(); MenuItem profileItem=new MenuItem(PROFILE); profileItem.setOnAction(e->showPage(PROFILE)); MenuItem logout=new MenuItem("Log Out"); logout.setOnAction(e->logOut()); menu.getItems().addAll(profileItem,logout);
        profile.setOnAction(e -> menu.show(profile, javafx.geometry.Side.TOP, 0, 0)); sidebar.getChildren().add(profile);
    }
    private void addNav(String name,String icon) {
        Button button=new Button(collapsed ? icon : icon+"  "+name); button.setMaxWidth(Double.MAX_VALUE); button.setAlignment(Pos.CENTER_LEFT);button.getStyleClass().add("sidebar-item");button.setTooltip(new Tooltip(name));button.setId("nav-"+name.replaceAll("\\W+","-"));button.setOnAction(e->showPage(name));navigation.put(name,button);sidebar.getChildren().add(button);
    }

    // ---- Home: account overview (console dashboard) + published event feed ----

    private Node home() {
        Label greeting=new Label("Welcome back, "+session.account().getFirstName()+"!");greeting.getStyleClass().add("page-title");
        Label message=new Label("Discover what's happening around you. Organizer level: "+session.customer().getOrganizerLevel());message.getStyleClass().add("secondary-text");
        Label balance=new Label("Wallet balance: "+Ui.php(session.account().getWalletBalance()));balance.getStyleClass().add("balance-label");
        HBox header=new HBox(12,new VBox(4,greeting,message),Ui.grow(),balance);header.getStyleClass().add("page-header");
        Node feed;
        try { List<Event> events=services.publishing().publishedEvents(); feed=events.isEmpty()?emptyState():eventGrid(events); }
        catch(Exception ex){ feed=errorState("Published events could not be loaded: "+Ui.message(ex)); }
        VBox body=new VBox(18,overview(),Ui.section("Published events"),feed);
        ScrollPane scroll=new ScrollPane(body);scroll.setFitToWidth(true);scroll.getStyleClass().add("content-scroll");
        Button create=new Button("+");create.getStyleClass().add("floating-action-button");create.setTooltip(new Tooltip("Create Event"));create.setOnAction(e->showPage(CREATE_EVENT));
        StackPane content=new StackPane(scroll,create);StackPane.setAlignment(create,Pos.BOTTOM_RIGHT);StackPane.setMargin(create,new Insets(0,28,28,0));
        BorderPane page=new BorderPane(content);page.setTop(header);page.getStyleClass().add("content-page");return page;
    }

    /** Same figures as the console's Account Overview. */
    private Node overview() {
        FlowPane tiles = new FlowPane(12, 12);
        try {
            int organized = services.events().eventsFor(session.customer()).size();
            long pending = services.reservations().forCustomer(session.customer()).stream()
                    .filter(reservation -> reservation.status() == BookingStatus.PENDING).count();
            tiles.getChildren().addAll(tile("Events organized", String.valueOf(organized)),
                    tile("Active tickets", String.valueOf(services.publishing().activeTicketCount(session.customer()))),
                    tile("Venue requests awaiting approval", String.valueOf(pending)));
        } catch (Exception ex) {
            tiles.getChildren().add(Ui.muted("Event figures are not available: " + Ui.message(ex)));
        }
        try {
            OrganizerEarnings earnings = services.earnings().forOrganizer(session.customer());
            tiles.getChildren().addAll(tile("Gross ticket revenue", Ui.php(earnings.grossTicketRevenue())),
                    tile("Platform commission (" + CommissionPolicy.configured().percent().toPlainString() + "%)", Ui.php(earnings.platformCommission())),
                    tile("Organizer net earnings", Ui.php(earnings.netEarnings())),
                    tile("Tickets sold", String.valueOf(earnings.ticketsSold())));
        } catch (Exception ex) {
            tiles.getChildren().add(Ui.muted("Organizer earnings are not available: " + Ui.message(ex)));
        }
        return tiles;
    }

    private static Node tile(String label, String value) { VBox tile = Ui.statTile(label, value); tile.setPrefWidth(220); return tile; }

    private Node eventGrid(List<Event> events) {
        GridPane grid=new GridPane();grid.setHgap(16);grid.setVgap(16);grid.setPadding(new Insets(4,0,84,0));
        grid.widthProperty().addListener((o,old,width)->{ if (Math.abs(width.doubleValue()-old.doubleValue())>40) populateGrid(grid,events,width.doubleValue()); });
        populateGrid(grid,events,900);return grid;
    }
    private void populateGrid(GridPane grid,List<Event> events,double width) {
        int columns=width>=1050?3:width>=690?2:1;grid.getChildren().clear();grid.getColumnConstraints().clear();for(int i=0;i<columns;i++){ColumnConstraints c=new ColumnConstraints();c.setPercentWidth(100.0/columns);c.setHgrow(Priority.ALWAYS);grid.getColumnConstraints().add(c);}for(int i=0;i<events.size();i++)grid.add(eventCard(events.get(i)),i%columns,i/columns);
    }
    private Node eventCard(Event event) {
        Label title=new Label(event.name());title.getStyleClass().add("event-card-title");title.setWrapText(true);
        String description=event.description().length()>130?event.description().substring(0,127)+"…":event.description();Label text=new Label(description);text.setWrapText(true);text.getStyleClass().add("secondary-text");
        Label details=new Label(event.type()+" • "+event.venue().name()+"\n"+event.date()+" • "+event.startTime()+"\n"+ticketSummary(event));details.setWrapText(true);details.getStyleClass().add("secondary-text");
        Button view=new Button("View Details");view.getStyleClass().add("secondary-button");view.setOnAction(e->showEvent(event));
        VBox card=new VBox(12,title,details,text,new Region(),view);VBox.setVgrow(card.getChildren().get(3),Priority.ALWAYS);card.getStyleClass().add("event-card");card.setMaxWidth(Double.MAX_VALUE);return card;
    }
    private Node emptyState(){Button create=new Button("Create New Event");create.getStyleClass().add("primary-button");create.setOnAction(e->showPage(CREATE_EVENT));VBox box=new VBox(12,new Label("✦"),Ui.title("No published events yet."),Ui.muted("Be the first to bring an event to life with DrawN."),create);box.setAlignment(Pos.CENTER);box.getStyleClass().add("empty-state");return box;}
    private Node errorState(String message){return new VBox(12,Ui.title("Unable to load events"),Ui.muted(message));}
    private String ticketSummary(Event event){try{List<TicketType> types=services.publishing().ticketTypes(event.id());if(types.isEmpty())return "Free event";BigDecimal low=types.stream().map(TicketType::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);int remaining=types.stream().mapToInt(TicketType::remaining).sum();return "From "+Ui.php(low)+" • "+remaining+" tickets available";}catch(Exception ex){return "Ticket information unavailable";}}
    private String initials(){String[] parts=session.customer().getName().trim().split("\\s+");return (parts[0].substring(0,1)+(parts.length>1?parts[parts.length-1].substring(0,1):"")).toUpperCase();}
    private String identifier(){return session.customer().getEmail()!=null?session.customer().getEmail():session.customer().getPhoneNumber();}
}
