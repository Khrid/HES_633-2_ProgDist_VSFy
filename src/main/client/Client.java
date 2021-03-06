package main.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import javafx.application.Application;
import javafx.embed.swing.JFXPanel;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import main.network.ExchangeEnum;
import main.network.NetworkInterfacePerso;
import main.tools.Tools;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Librairie qui contient toutes les fonctions du clients:
 * -connexion au serveur
 * -exécution des commandes utilisateur
 * -lecture des fichiers distants
 * -envoi/réception de données en mode P2P
 */
public class Client implements Serializable {

    public static final long serialVersionUID = 42L;

    private static final String BASE_DIR = "/tmp/vsfy";

    private static final List<String> EXTENSIONS_ALLOWED = Arrays.asList("mp3","wav","aac");

    private String uuid; // identifiant du client, pour s'en sortir quand on lance plusieurs clients sur le même PC

    private InetAddress address; // pour gérer l'adresse IP

    private int port = 50001; // port du serveur

    transient private InetAddress serverAddress; // adresse IP du serveur

    transient private Socket exchangeSocket; // socket d'échange

    transient private DataInputStream dataIn; // pour les données venant du socket

    transient private DataOutputStream dataOut; // pour les données sortant vers le socket

    transient ArrayList<Client> knownClients = new ArrayList<>(); // Liste des clients

    private ArrayList<File> files = new ArrayList<>(); // liste des fichiers que le client peut mettre à dispo

    private int p2pPort;

    transient private DataInputStream p2pDataIn; // pour les données venant du socket

    transient private DataOutputStream p2pDataOut; // pour les données sortant vers le socket

    transient private Socket p2pExchangeSocket;

    transient private MediaPlayer mp;

    transient private String nowPlaying;

    /**
     * Contructeur de la classe Client
     * @param nip Toutes les informations de la carte réseau séléctionnée par l'utilisateur
     */
    public Client(NetworkInterfacePerso nip) {
        address = nip.getAddress(); // récupération de l'adresse IP de la machine
        System.out.println("Client ip " + nip.getIp()); // info utilisateur concernant l'IP
    }

    /**
     * Méthode de connexion au serveur
     * @param serverName l'adresse IP du serveur
     */
    public void connectToServer(String serverName) {
        try {
            serverAddress = InetAddress.getByName(serverName); // récupération de l'objet InetAdress pour le serveur
            System.out.print("Connecting to server " + serverAddress.getHostAddress() + ":" + port + ". ");
            exchangeSocket = new Socket(serverAddress, port); // socket d'échange entre le client et le serveur
            dataIn = new DataInputStream(exchangeSocket.getInputStream()); // flux d'échange - entrée
            dataOut = new DataOutputStream(exchangeSocket.getOutputStream()); // flux d'échange - sortie
            dataOut.writeUTF(ExchangeEnum.HELLO.command); // on fait le handshake du début
            dataOut.writeUTF(
                    new GsonBuilder().create().toJson(this)); // on s'envoie au serveur pour qu'il ait les infos du client
            this.uuid = dataIn.readUTF();
            System.out.println("Done."); // info utilisateur que la connexion est établie
            System.out.println("Got my UUID from server : " + this.uuid);
            getClients();
        } catch (IOException e) {
            System.out.println("Could not establish a connection with the server.");
            System.exit(-1);
        }
    }

    /**
     * Méthode qui gère les commandes envoyées par le client
     */
    public void communicate() {
        Scanner scanner = new Scanner(System.in);
        boolean interrupted = false;
        listActions();
        while (true) {
            if (interrupted) {
                System.exit(0);
                break;
            }
            System.out.print("Enter action : ");
            String command = scanner.nextLine().toUpperCase();
            try {
                switch (ExchangeEnum.valueOf(command)) {
                    case LIST_ACTIONS:
                        listActions();
                        break;
                    case BYE:
                        System.out.print("Disconnecting from server. ");
                        dataOut.writeUTF(command);
                        interrupted = true;
                        System.out.println("Done.");
                        break;
                    case STOP:
                        if (mp != null && mp.getStatus().equals(MediaPlayer.Status.PLAYING)) {
                            System.out.println("Stopped the player.");
                            mp.stop();
                        } else {
                            System.out.println("Media player not active.");
                        }
                        break;
                    case PAUSE:
                        if (mp != null && mp.getStatus().equals(MediaPlayer.Status.PLAYING)) {
                            System.out.println("Paused the player.");
                            mp.pause();
                        } else {
                            System.out.println("Media player not active.");
                        }
                        break;
                    case RESUME:
                        if (mp != null && mp.getStatus().equals(MediaPlayer.Status.PAUSED)) {
                            System.out.println("Resumed the player.");
                            mp.play();
                        } else {
                            System.out.println("Media player not active/paused.");
                        }
                        break;
                    case NOW_PLAYING:
                        if (mp != null && mp.getStatus().equals(MediaPlayer.Status.PLAYING)) {
                            int current = (int) mp.getCurrentTime().toSeconds();
                            int duration = (int) mp.getMedia().getDuration().toSeconds();
                            System.out.println("Now playing " + nowPlaying + ", " + Tools.secondsToMmss(current) + " / " + Tools.secondsToMmss(duration));
                        } else {
                            System.out.println("Media player not active.");
                        }
                        break;
                    case PLAY:
                        // Scanne le répertoire pour trouver les fichiers médias et cherche les clients disponibles
                        scanFolder();
                        getClients();

                        // Bloc si des clients sont disponibles
                        if (!knownClients.isEmpty()) {
                            listFiles();
                            // Bloc pour choisir le client auquel se connecter
                            System.out.print("Enter target UUID : ");
                            String target = scanner.nextLine();
                            Client client = null;
                            for (Client c : knownClients) {
                                if (c.getUuid().equalsIgnoreCase(target) && !this.getUuid().equalsIgnoreCase(target)) {
                                    client = c;
                                }
                            }
                            // Bloc pour choisir le fichier à lire
                            if (client != null) {
                                System.out.println("Available files for selected client : ");
                                for (File f : client.getFiles()) {
                                    System.out.println("\t" + f.getName());
                                }
                                System.out.print("Select file to play : ");
                                target = scanner.nextLine();
                                File fTarget = null;
                                boolean exists = false;
                                for (File f : client.getFiles()) {
                                    if (f.getName().equalsIgnoreCase(target)) {
                                        fTarget = f;
                                        break;
                                    }
                                }
                                if (fTarget != null) { // si le client possède bien le fichier demandé
                                    serverAddress = InetAddress
                                            .getByName(client.getIp()); // récupération de l'objet InetAdress pour le serveur
                                    p2pExchangeSocket = new Socket(serverAddress, client.getP2pPort());
                                    p2pDataIn = new DataInputStream(
                                            p2pExchangeSocket.getInputStream()); // flux d'échange - entrée
                                    p2pDataOut = new DataOutputStream(
                                            p2pExchangeSocket.getOutputStream()); // flux d'échange - sortie
                                    p2pDataOut.writeUTF(target);
                                    String ext = target.substring(target.lastIndexOf('.') + 1);
                                    JFXPanel fxPanel = new JFXPanel(); // utilisé uniquement pour gérer les fichiers audios autre que .wav

                                    // Bloc qui envoie le fichier et commence la lecture
                                    InputStream bufferedIn = new BufferedInputStream(p2pDataIn);
                                    File temp = null;
                                    try {
                                        temp = File.createTempFile("tmp", "." + ext, null);
                                        temp.deleteOnExit();
                                        FileOutputStream fos = new FileOutputStream(temp);
                                        byte[] buffer = new byte[8192];
                                        int count;
                                        while ((count = p2pDataIn.read(buffer)) >= 0) {
                                            fos.write(buffer, 0, count);
                                        }
                                        fos.close();
                                        Media m = new Media(temp.toURI().toURL().toString());
                                        mp = new MediaPlayer(m);
                                        //mp.setCycleCount(MediaPlayer.INDEFINITE);
                                        System.out.println("\uD83C\uDFB6 Now playing " + target + " \uD83C\uDFB6");
                                        mp.play();
                                        nowPlaying = target;

                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                } else {
                                    System.out.println("Client does not have that file.");
                                }
                            } else {
                                System.out.println("Client not found.");
                            }
                        } else {
                            System.out.println("No other clients to connect to.");
                        }
                        break;
                    default:
                        System.out.println("Action unkown.");
                        listActions();
                        break;
                }
            } catch (IOException e) {
                System.out.println("Lost connection with the server.");
                System.exit(-1);
            } catch (IllegalArgumentException iae) {
                //iae.printStackTrace();
                System.out.println("Action unkown.");
                listActions();
            }

        }
    }
    /**
     * Méthode qui va chercher les tous les fichiers disponibles dans le répertoire des médias
     */
    private void listFiles() {
        for (Client c : knownClients) {
            if (!c.getUuid().equalsIgnoreCase(this.getUuid())) {
                System.out.println("Client " + c.getUuid()/* + " - IP " + c.getIp() + ":"+c.getP2pPort()*/ + " : ");
                if (c.getFiles().size() > 0) {
                    for (File f : c.getFiles()) {
                        System.out.println("\t" + f.getName() + " - " + f.length() / 1024 + "ko - ");
                    }
                } else {
                    System.out.println("\tNothing to share");
                }
            }
        }
    }

    /**
     * Retourne le port de communication P2P
     * @return le numéro du port de communication P2P
     */
    public int getP2pPort() {
        return this.p2pPort;
    }

    /**
     * Retourne les fichiers disponibles pour la lecture
     * @return une Collection d'objets File
     */
    public Collection<File> getFiles() {
        return files;
    }

    /**
     * Retourne l'UUID du client
     * @return String de l'UUID du client
     */
    public String getUuid() {
        return uuid;
    }

    /**
     * Affecte l'UUID au client
     * @param uuid une chaîne de caractère contenant l'UUID
     */
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    /**
     * Retourne l'adresse IP du client
     * @return une chaîne de caractère de l'IP du client
     */
    public String getIp() {
        return address.getHostAddress();
    }

    /**
     * Affiche la liste des actions possibles à l'utilisateur
     */
    public void listActions() {
        System.out.println("List of possible actions (not case sensitive) : ");
        for (String s : ExchangeEnum.getAvailableActions()) {
            System.out.print(s + " - ");
        }
        System.out.println("");
    }

    /**
     * Méthode qui va chercher tous les clients prêts à diffuser des médias
     */
    private void getClients() {
        try {
            dataOut.writeUTF(ExchangeEnum.GET_CLIENTS.command);
            String ret = dataIn.readUTF();
            knownClients = new Gson().fromJson(new JsonReader(new StringReader(ret)), new TypeToken<List<Client>>() {
            }.getType());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Méthode qui remonte tous les fichiers gérés par VSFy selon les extensions autorisées
     */
    public void scanFolder() {
        Path path = Paths.get(BASE_DIR);
        if (Files.exists(path)) { // si le répertoire existe
            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(Paths.get(BASE_DIR))) {
                if (directoryStream.iterator().hasNext()) { // si le répertoire a des fichiers dedans
                    File folder = new File(BASE_DIR);
                    for (File f : Objects.requireNonNull(folder.listFiles())) {
                        if (f.isFile() && EXTENSIONS_ALLOWED.contains(getFileExtension(f.getName()))) {
                            files.add(f);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("No files to share in directory \"" + BASE_DIR + "\"");
            }
        } else {
            System.out.println("Directory \"" + BASE_DIR + "\" does not exist. Carrying on.");
        }
    }
    /**
     * Méthode qui retourne l'extension du fichier fourni en paramètre
     * @param filename le nom du fichier dont on veut obtenir l'extension
     * @return une chaîne de caractère de l'extension du fichier
     */
    private String getFileExtension(String filename) {
        return Optional.ofNullable(filename).filter(f -> f.contains(".")).map(f -> f.substring(filename.lastIndexOf(".") + 1)).orElse("other");
    }

    /**
     * Méthode qui démarre le thread du serveur P2P afin de diffuser un média
     */
    public void startP2PServer() {
        try {
            ServerSocket p2pSocket = new ServerSocket(0, 10, address);
            this.p2pPort = p2pSocket.getLocalPort();
            //System.out.println(p2pPort);
            Thread t = new Thread(new AcceptClient(p2pSocket));
            t.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
