package com.vaidhyamegha.data_cloud.kg;


import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.util.FileManager;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.*;
import java.sql.*;
import java.util.List;
import java.util.Properties;

import static org.kohsuke.args4j.OptionHandlerFilter.ALL;

/**
 * Accept either a trial id or Pubmed Article id or Symptom (MeSH) or Disease (MeSH).
 * Find matches to the other 3
 */
public class App {

    private static final String PIPE = "\\|";
    @Option(name = "-o", aliases = "--output-rdf", usage = "Path to the final RDF file", required = false)
    private File out = new File("data/open_knowledge_graph_on_clinical_trials/vaidhyamegha_open_kg_clinical_trials.nt");

    @Option(name = "-l", aliases = "--output-trials-list", usage = "Path to the list of trial ids file", required = false)
    private File trials = new File("data/open_knowledge_graph_on_clinical_trials/vaidhyamegha_clinical_trials.csv");

    @Option(name = "-v", aliases = "--mesh-vocab-rdf", usage = "Path to the downloaded MeSH Vocabulary Turtle file.", required = false)
    private String meshVocab = "data/open_knowledge_graph_on_clinical_trials/vocabulary_1.0.0.ttl";

    @Option(name = "-co", aliases = "--mrcoc-sorted-file", usage = "Path to sorted MRCOC detailed co occurrence file for selected fields.", required = false)
    private String mrcoc = "data/open_knowledge_graph_on_clinical_trials/detailed_CoOccurs_2021_selected_fields_sorted.txt";

    @Option(name = "-m", aliases = "--mesh-rdf", usage = "Path to the downloaded MeSH RDF file.", required = false)
    private String meshRDF = "data/open_knowledge_graph_on_clinical_trials/mesh2022.nt";

    @Option(name = "-b", aliases = "--mode", usage = "Build RDF or query pre-built RDF?", required = false)
    private MODE mode = MODE.BUILD;

    @Option(name = "-t", aliases = "--trial-id", usage = "Clinical trial's registered id.", required = false)
    private String trial;

    @Option(name = "-p", aliases = "--article-id", usage = "PubMed id for the article.", required = false)
    private String article;

    @Option(name = "-s", aliases = "--symptom-id", usage = "MeSH id for a symptom.", required = false)
    private String symptom;

    @Option(name = "-d", aliases = "--disease-id", usage = "MeSH id for a disease.", required = false)
    private String disease;

    private Properties prop = null;

    public static void main(String[] args) throws IOException {
        new App().doMain(args);
    }

    public void doMain(String[] args) throws IOException {
        CmdLineParser parser = new CmdLineParser(this);

        try {
            if (mode == MODE.BUILD) {
                parser.parseArgument(args);

                ClassLoader cl = App.class.getClassLoader();
                prop = readProperties(cl);

                Model model = ModelFactory.createDefaultModel();

                addAllTrials(model);

                FileManager.getInternal().addLocatorClassLoader(cl);

                Model vocab = ModelFactory.createDefaultModel();
                vocab.read(meshVocab, "TURTLE");

                Model meshModel = ModelFactory.createDefaultModel();
                meshModel.read(meshRDF, "NT");

                addTrialConditions(model, meshModel);
                addTrialInterventions(model, meshModel);

                addMeSHCoOccurrences(model, meshModel);
                RDFDataMgr.write(new FileOutputStream(out), model, Lang.NT);
            } else {
                throw new UnsupportedOperationException("Non-build modes are not yet supported");
            }
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("java App [options...] arguments...");
            parser.printUsage(System.err);
            System.err.println();

            System.err.println("  Example: java App" + parser.printExample(ALL));
        }
    }

    private void addMeSHCoOccurrences(Model model, Model meshModel) { //TODO: we will use mesHModel more appropriately soon to pick the RDF node directly from there.
        Property pMeSHDUI = model.createProperty("MeSH_DUI");
        String qAllArticles = prop.getProperty("all_articles");
        String line = "";

        try (BufferedReader br = new BufferedReader(new FileReader(mrcoc));
             Connection conn = DriverManager.getConnection(prop.getProperty("aact_url"),
                     prop.getProperty("user"), prop.getProperty("password"));
             PreparedStatement sAllArticles = conn.prepareStatement(qAllArticles); ) {

            ResultSet resultSet = sAllArticles.executeQuery();

            while (resultSet.next()) {
                String article = resultSet.getString("article");

                if(line != null && !article.equals(line.split(PIPE)[0]))
                    while((line = br.readLine())!= null) if (article.equals(line.split(PIPE)[0])) break;

                if (line == null) break;

                do {
                    String[] ids = line.split(PIPE);

                    Resource r = createResource(model, ids[0], RESOURCE.PUBMED_ARTICLE);
                    Resource dui1 = createResource(model, ids[1], RESOURCE.MESH_DUI);
                    Resource dui2 = createResource(model, ids[2], RESOURCE.MESH_DUI);

                    model.add(r, pMeSHDUI, dui1);
                    model.add(r, pMeSHDUI, dui2);

                    line = br.readLine();
                } while (line!= null && article.equals(line.split(PIPE)[0]));
            }
        } catch (SQLException e) {
            System.err.format("SQL State: %s\n%s", e.getSQLState(), e.getMessage());
            throw new RuntimeException("Sorry, unable to connect to database");
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Sorry, couldn't read MeSH co-occurrence links");
        }
    }

    private void addAllTrials(Model model) {
        Property pTrialId = model.createProperty("TrialId");
        String qTrialIds = prop.getProperty("trial_ids");
        String qTrialArticles = prop.getProperty("select_trial_articles");

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(trials));
                Connection conn = DriverManager.getConnection(prop.getProperty("aact_url"),
                     prop.getProperty("user"), prop.getProperty("password"));
                PreparedStatement sTrialIds = conn.prepareStatement(qTrialIds);
                PreparedStatement sTrialArticles = conn.prepareStatement(qTrialArticles); ) {

            ResultSet resultSet = sTrialIds.executeQuery();

            while (resultSet.next()) {
                String trialId = resultSet.getString("trial_id");
                Resource r = createResource(model, trialId, RESOURCE.TRIAL);

                model.add(r, pTrialId, trialId);

                bw.write(trialId + "\n");

                insertTrialArticles(trialId);
            }

            resultSet = sTrialArticles.executeQuery();

            while (resultSet.next()) {
                String trial = resultSet.getString("trial");
                Array pubmedArticles = resultSet.getArray("pubmed_articles");

                Integer[] articles = (Integer[]) pubmedArticles.getArray();

                addTrialArticles(model, trial, articles);
            }
        } catch (SQLException e) {
            System.err.format("SQL State: %s\n%s", e.getSQLState(), e.getMessage());
            throw new RuntimeException("Sorry, unable to connect to database");
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Sorry, couldn't write to trials file or trials-articles file");
        }
    }

    private Resource createResource(Model model, String rId, RESOURCE rType) {
        switch (rType) {
            case TRIAL:
                String uri = "https://clinicaltrials.gov/ct2/show/" + rId;

                if (!rId.startsWith("NCT"))
                    uri = "https://www.who.int/clinical-trials-registry-platform/" + rId;

                return model.createResource(uri);
            case PUBMED_ARTICLE:
                uri = "https://pubmed.ncbi.nlm.nih.gov/" + rId;
                return model.createResource(uri);
            case MESH_DUI:
                uri = "https://meshb.nlm.nih.gov/record/ui?ui=" + rId;
                return model.createResource(uri);
            default:
                throw new RuntimeException("Unsupported resource type " + rType);
        }
    }

    private void addTrialArticles(Model model, String trial, Integer[] articles) {
        Property pPubMedArticle = model.createProperty("Pubmed_Article");
        Property pArticleId = model.createProperty("ArticleId");

        for (Integer a : articles) {
            Resource rArticle = createResource(model,String.valueOf(a), RESOURCE.PUBMED_ARTICLE);

            model.add(rArticle, pArticleId, String.valueOf(a));

            Resource rTrial = createResource(model, trial, RESOURCE.TRIAL);

            model.add(rTrial, pPubMedArticle, rArticle);
        }
    }

    private void insertTrialArticles(String trialId) {
        if (Math.random() > 0.99999) { // constraining so that only a small number of Entrez API calls are made. TODO : Optimize this by checking if an id is already attempted before.
            List<Integer> articles = EntrezClient.getPubMedIds(trialId).getIdList();

            insertTrialPubMedArticles(trialId, articles);
        }
    }

    private void insertTrialPubMedArticles(String trialId, List<Integer> s) {
        try (Connection c = DriverManager.getConnection(prop.getProperty("aact_url"),
                prop.getProperty("user"), prop.getProperty("password"));) {
            PreparedStatement stmt = c.prepareStatement(prop.getProperty("insert_trial_articles"));

            Array array = c.createArrayOf("integer", s.toArray());

            stmt.setString(1, trialId);
            stmt.setArray(2, array);

            stmt.execute();
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Sorry, couldn't write to trials file or trials-articles database table");
        }
    }

    private void addTrialConditions(Model model, Model meshModel) {
        String query = prop.getProperty("aact_browse_conditions");
        Property p = model.createProperty("Condition");

        addTrialToMeSHLinks(model, meshModel, query, p);
    }

    private void addTrialInterventions(Model model, Model meshModel) {
        String query = prop.getProperty("aact_browse_interventions");
        Property p = model.createProperty("Intervention");

        addTrialToMeSHLinks(model, meshModel, query, p);
    }

    private void addTrialToMeSHLinks(Model model, Model meshModel, String query, Property p) {
        try (Connection conn = DriverManager.getConnection(prop.getProperty("aact_url"),
                prop.getProperty("user"), prop.getProperty("password"));
             PreparedStatement preparedStatement = conn.prepareStatement(query)) {

            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String trialId = resultSet.getString("nct_id");
                String conditionMeSHTerm = resultSet.getString("mesh_term");

                Resource r = createResource(model, trialId, RESOURCE.TRIAL);

                Literal literal = meshModel.createLiteral(conditionMeSHTerm, "en");
                Selector selector = new SimpleSelector(null, null, literal);
                StmtIterator si = meshModel.listStatements(selector);

                if (si.hasNext()) {
                    Statement s = si.nextStatement();

                    model.add(s);
                    model.add(r, p, s.getSubject());
                }
            }
        } catch (SQLException e) {
            System.err.format("SQL State: %s\n%s", e.getSQLState(), e.getMessage());
            throw new RuntimeException("Sorry, unable to connect to database");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Sorry, unable to connect to database");
        }
    }

    private Properties readProperties(ClassLoader cl) {
        try (InputStream input = cl.getResourceAsStream("config.properties")) {

            Properties prop = new Properties();

            if (input == null) throw new RuntimeException("Sorry, unable to find config.properties");

            prop.load(input);

            return prop;
        } catch (IOException ex) {
            ex.printStackTrace();
            throw new RuntimeException("Sorry, unable to find config.properties");
        }
    }
}
