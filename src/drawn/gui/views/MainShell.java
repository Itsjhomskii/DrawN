package drawn.gui.views;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
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

import java.math.BigDecimal;
import javafx.util.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single-stage customer shell with replaceable center content. */
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
    private boolean collapsed;
    private String activePage = "Home";
    private Button sidebarToggle;
    private CustomerMenuPages customerPages;

    public MainShell(DrawNApplication app, AppServices services, AppSession session) { this.app=app;this.services=services;this.session=session; }

    public Parent view() {
        root.getStyleClass().add("app-root");
        root.setLeft(sidebar);
        customerPages = new CustomerMenuPages(services, session, this::showPage);
        sidebar.getStyleClass().add("sidebar");
        rebuildSidebar();
        showPage("Home");
        return root;
    }

    AppServices services() { return services; }
    AppSession session() { return session; }
    String currentPage() { return activePage; }
    Node currentContent() { return root.getCenter(); }

    void showEvent(Event event) {
        new EventDetailsView(event, services, session, () -> showPage("Explore Events")).show();
    }

    void logOut() { session.clear(); app.showLogin(); }

    private void rebuildSidebar() {
        sidebar.getChildren().clear();
        navigation.clear();
        double width = collapsed ? 72 : 250;
        sidebar.setPrefWidth(width); sidebar.setMinWidth(width); sidebar.setMaxWidth(width);
        HBox header = new HBox(); header.setAlignment(Pos.CENTER_LEFT);
        Label brand = new Label("DrawN"); brand.getStyleClass().add("sidebar-brand"); brand.setVisible(!collapsed); brand.setManaged(!collapsed);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        String toggleIcon = collapsed ? "fi fi-br-menu-burger" : "fi fi-rr-angle-left";
        sidebarToggle = new Button("", new DashboardIcon(toggleIcon, 18)); sidebarToggle.getStyleClass().addAll("icon-button", "hamburger-toggle"); sidebarToggle.setTooltip(new Tooltip(collapsed ? "Expand sidebar" : "Collapse sidebar"));
        sidebarToggle.setOnAction(e -> toggleSidebar()); header.getChildren().addAll(brand,spacer,sidebarToggle); sidebar.getChildren().add(header);
        DashboardNavigation.items().forEach(item -> addNav(item.label(), item.iconClass()));
        Region grow = new Region(); VBox.setVgrow(grow, Priority.ALWAYS); sidebar.getChildren().add(grow);
        Button profile = new Button(collapsed ? initials() : initials()+"  "+session.customer().getName()+"\n"+identifier());
        profile.getStyleClass().add("profile-section"); profile.setMaxWidth(Double.MAX_VALUE); profile.setAlignment(Pos.CENTER_LEFT); profile.setTooltip(new Tooltip(session.customer().getName()));
        ContextMenu menu = new ContextMenu(); MenuItem profileItem=new MenuItem("My Profile"); profileItem.setOnAction(e->showPage("My Profile")); MenuItem logout=new MenuItem("Log Out"); logout.setOnAction(e->{session.clear();app.showLogin();}); menu.getItems().addAll(profileItem,logout);
        profile.setOnAction(e -> menu.show(profile, javafx.geometry.Side.TOP, 0, 0)); sidebar.getChildren().add(profile);
    }

    private void toggleSidebar() {
        double from = collapsed ? 72 : 250;
        collapsed = !collapsed;
        double to = collapsed ? 72 : 250;
        rebuildSidebar();
        sidebar.setMinWidth(72);
        sidebar.setPrefWidth(from);
        sidebar.setMaxWidth(from);

        DashboardIcon icon = (DashboardIcon) sidebarToggle.getGraphic();
        icon.setRotate(collapsed ? -90 : 90);
        icon.setOpacity(0);
        FadeTransition iconFade = new FadeTransition(Duration.millis(240), icon);
        iconFade.setFromValue(0);
        iconFade.setToValue(1);
        RotateTransition iconRotation = new RotateTransition(Duration.millis(240), icon);
        iconRotation.setFromAngle(collapsed ? -90 : 90);
        iconRotation.setToAngle(0);
        ParallelTransition iconReveal = new ParallelTransition(iconFade, iconRotation);
        iconReveal.play();

        Timeline resize = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(sidebar.prefWidthProperty(), from),
                        new KeyValue(sidebar.maxWidthProperty(), from)),
                new KeyFrame(Duration.millis(260),
                        new KeyValue(sidebar.prefWidthProperty(), to, Interpolator.EASE_BOTH),
                        new KeyValue(sidebar.maxWidthProperty(), to, Interpolator.EASE_BOTH))
        );
        resize.setOnFinished(event -> {
            sidebar.setMinWidth(to);
            sidebar.setPrefWidth(to);
            sidebar.setMaxWidth(to);
            showPage(activePage);
        });
        resize.play();
        for (Button button : navigation.values()) {
            FadeTransition fade = new FadeTransition(Duration.millis(180), button);
            fade.setFromValue(0.55);
            fade.setToValue(1);
            fade.setDelay(Duration.millis(60));
            fade.play();
        }
    }
    private void addNav(String name,String icon) {
        Button button = DashboardNavigation.button(new DashboardNavigation.Item(name, icon), collapsed, this::showPage);
        navigation.put(name, button);
        if (name.equals(activePage)) button.getStyleClass().add("sidebar-item-active");
        sidebar.getChildren().add(button);
    }
    public void showPage(String name) {
        activePage = name;
        navigation.values().forEach(button -> button.getStyleClass().remove("sidebar-item-active"));
        Button active=navigation.get(name);if(active!=null)active.getStyleClass().add("sidebar-item-active");
        if (name.equals("Home")) root.setCenter(home());
        else {
            Node workflow;
            if (name.equals(CREATE_EVENT)) {
                workflow = new CreateEventPage(this).view();
            } else if (name.equals(REQUEST_VENUE)) {
                workflow = new VenueRequestPage(this).view();
            } else if (name.equals(MY_EVENTS)) {
                workflow = new MyEventsPage(this).view();
            } else if (name.equals(VENUES)) {
                workflow = new VenueReservationsPage(this).view();
            } else if (name.equals(WALLET)) {
                workflow = new WalletPage(this).view();
            } else {
                workflow = customerPages.page(name);
            }
            root.setCenter(workflow == null ? placeholder(name) : workflow);
        }
    }
    private Node home() {
        Label greeting=new Label("Welcome back, "+session.account().getFirstName()+"!");greeting.getStyleClass().add("page-title");
        Label message=new Label("Discover what's happening around you.");message.getStyleClass().add("secondary-text");
        Button balance = new Button("Wallet balance: PHP " + money(session.account().getWalletBalance()), new DashboardIcon("fi fi-rr-wallet", 18));
        balance.getStyleClass().add("wallet-balance-button");
        balance.setTooltip(new Tooltip("Open Wallet & Transactions"));
        balance.setAccessibleText("Wallet balance PHP " + money(session.account().getWalletBalance()) + ". Open Wallet and Transactions.");
        balance.setOnAction(event -> showPage("Wallet & Transactions"));
        HBox header=new HBox(12,new VBox(4,greeting,message),new Region(),balance);HboxGrow(header);header.getStyleClass().add("page-header");
        Node feed;
        try { List<Event> events=services.publishing().publishedEvents(); feed=events.isEmpty()?emptyState():eventGrid(events); }
        catch(Exception ex){ feed=errorState("Published events could not be loaded: "+ex.getMessage()); }
        Button create=new Button("", new DashboardIcon("fi fi-rr-add", 23));create.getStyleClass().add("floating-action-button");create.setTooltip(new Tooltip("Create Event"));create.setAccessibleText("Create event");create.setOnAction(e->showPage(CREATE_EVENT));
        ScaleTransition hoverScale = new ScaleTransition(Duration.millis(140), create);
        hoverScale.setInterpolator(Interpolator.EASE_BOTH);
        create.setOnMouseEntered(e -> animateCreateButton(hoverScale, 1.07));
        create.setOnMouseExited(e -> animateCreateButton(hoverScale, 1.0));
        StackPane content=new StackPane(feed,create);StackPane.setAlignment(create,Pos.BOTTOM_RIGHT);StackPane.setMargin(create,new Insets(0,28,28,0));
        content.getStyleClass().add("events-section");
        BorderPane page=new BorderPane(content);page.setTop(header);BorderPane.setMargin(content,new Insets(16,0,0,0));page.getStyleClass().add("content-page");return page;
    }
    private static void animateCreateButton(ScaleTransition transition, double scale) {
        transition.stop();
        transition.setToX(scale);
        transition.setToY(scale);
        transition.playFromStart();
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
        Label details=new Label(CustomerText.words(event.type())+" • "+event.venue().name()+"\n"+event.date()+" • "+CustomerText.time(event.startTime())+"\n"+ticketSummary(event));details.setWrapText(true);details.getStyleClass().add("secondary-text");
        Button view=new Button("View Details");view.getStyleClass().add("secondary-button");view.setOnAction(e->new EventDetailsView(event,services,session,()->showPage(activePage)).show());
        VBox card=new VBox(12,title,details,text,new Region(),view);VBox.setVgrow(card.getChildren().get(3),Priority.ALWAYS);card.getStyleClass().add("event-card");card.setMaxWidth(Double.MAX_VALUE);return card;
    }
    private Node emptyState(){Button create=new Button("Create New Event");create.getStyleClass().addAll("primary-button", "create-event-button");create.setOnAction(e->showPage(CREATE_EVENT));VBox box=new VBox(12,new Label("✦"),title("No published events yet."),subtitle("Be the first to bring an event to life with DrawN."),create);box.setAlignment(Pos.CENTER);box.getStyleClass().add("empty-state");return box;}
    private Node placeholder(String page){return new VBox(12,title(page),subtitle(page.equals("Create Event")?"Create Event is pending GUI migration. Use the console workflow to preserve approved-reservation and confirmation requirements.":"This customer workflow is pending GUI migration. No data or payment will be changed here."));}
    private Node errorState(String message){return new VBox(12,title("Unable to load events"),subtitle(message));}
    private String ticketSummary(Event event){try{List<TicketType> types=services.publishing().ticketTypes(event.id());if(types.isEmpty())return "Free event";BigDecimal low=types.stream().map(TicketType::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);int remaining=types.stream().mapToInt(TicketType::remaining).sum();return "From PHP "+money(low)+" • "+remaining+" tickets available";}catch(Exception ignored){return "Ticket information unavailable";}}
    private static void HboxGrow(HBox box){for(Node node:box.getChildren())if(node instanceof Region region)HBox.setHgrow(region,Priority.ALWAYS);}
    private String initials(){String[] parts=session.customer().getName().trim().split("\\s+");return (parts[0].substring(0,1)+(parts.length>1?parts[parts.length-1].substring(0,1):"")).toUpperCase();}
    private String identifier(){return session.customer().getEmail()!=null?session.customer().getEmail():session.customer().getPhoneNumber();}
    private static String money(BigDecimal amount){return amount.setScale(2).toPlainString();}
    private static Label title(String text){Label label=new Label(text);label.getStyleClass().add("page-title");return label;}private static Label subtitle(String text){Label label=new Label(text);label.getStyleClass().add("secondary-text");label.setWrapText(true);return label;}
}
