package net.sf.jabref.logic.importer.fetcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jabref.logic.help.HelpFile;
import net.sf.jabref.logic.importer.FetcherException;
import net.sf.jabref.logic.importer.IdBasedParserFetcher;
import net.sf.jabref.logic.importer.Parser;
import net.sf.jabref.logic.importer.ParserResult;
import net.sf.jabref.logic.importer.SearchBasedFetcher;
import net.sf.jabref.logic.importer.fileformat.MedlineImporter;
import net.sf.jabref.logic.l10n.Localization;
import net.sf.jabref.model.entry.BibEntry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.utils.URIBuilder;

/**
 * Fetch or search from PubMed <a href=http://www.ncbi.nlm.nih.gov/sites/entrez/>www.ncbi.nlm.nih.gov</a>
 * The MedlineFetcher fetches the entries from the PubMed database.
 * See <a href=http://help.jabref.org/en/MedlineRIS>help.jabref.org</a> for a detailed documentation of the available fields.
 */
public class MedlineFetcher implements IdBasedParserFetcher, SearchBasedFetcher {

    private static final Log LOGGER = LogFactory.getLog(MedlineFetcher.class);

    private static final Pattern ID_PATTERN = Pattern.compile("<Id>(\\d+)</Id>");
    private static final Pattern COUNT_PATTERN = Pattern.compile("<Count>(\\d+)<\\/Count>");

    private int numberOfResultsFound;

    /**
     * Replaces all commas in a given string with " AND "
     *
     * @param query input to remove commas
     * @return input without commas
     */
    private static String replaceCommaWithAND(String query) {
        return query.replaceAll(", ", " AND ").replaceAll(",", " AND ");
    }

    /**
     * When using 'esearch.fcgi?db=<database>&term=<query>' we will get a list of IDs matching the query.
     * Input: Any text query (&term)
     * Output: List of UIDs matching the query
     *
     * @see <a href="https://www.ncbi.nlm.nih.gov/books/NBK25500/">www.ncbi.nlm.nih.gov/books/NBK25500/</a>
     */
    private List<String> getPubMedIdsFromQuery(String query) throws FetcherException {
        List<String> idList = new ArrayList<>();
        try {
            URL ncbi = createSearchUrl(query);
            BufferedReader in = new BufferedReader(new InputStreamReader(ncbi.openStream()));
            String inLine;
            //Everything relevant is listed before the IdList. So we break the loop right after the IdList tag closes.
            while ((inLine = in.readLine()) != null) {
                if (inLine.contains("</IdList>")) {
                    break;
                }
                Matcher idMatcher = ID_PATTERN.matcher(inLine);
                if (idMatcher.find()) {
                    idList.add(idMatcher.group(1));
                }
                Matcher countMatcher = COUNT_PATTERN.matcher(inLine);
                if (countMatcher.find()) {
                    numberOfResultsFound = Integer.parseInt(countMatcher.group(1));
                }
            }
            return idList;
        } catch (IOException | URISyntaxException e) {
            throw new FetcherException("Unable to get PubMed IDs", e);
        }
    }

    @Override
    public String getName() {
        return "Medline";
    }

    @Override
    public HelpFile getHelpPage() {
        return HelpFile.FETCHER_MEDLINE;
    }

    @Override
    public URL getURLForID(String identifier) throws URISyntaxException, MalformedURLException, FetcherException {
        URIBuilder uriBuilder = new URIBuilder("http://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi");
        uriBuilder.addParameter("db", "pubmed");
        uriBuilder.addParameter("retmode", "xml");
        uriBuilder.addParameter("id", identifier);
        return uriBuilder.build().toURL();
    }

    @Override
    public Parser getParser() {
        return new MedlineImporter();
    }

    @Override
    public void doPostCleanup(BibEntry entry) {
        entry.clearField("journal-abbreviation");
        entry.clearField("status");
        entry.clearField("copyright");
    }

    @Override
    public List<BibEntry> performSearch(String query) throws FetcherException {
        List<BibEntry> entryList = new LinkedList<>();

        if (query.isEmpty()) {
            return Collections.emptyList();
        } else {
            String searchTerm = replaceCommaWithAND(query);

            //searching for pubmed ids matching the query
            List<String> idList = getPubMedIdsFromQuery(searchTerm);

            if (idList.isEmpty()) {
                LOGGER.info(Localization.lang("No results found."));
            }
            if (numberOfResultsFound > 20) {
                LOGGER.info(numberOfResultsFound + " results found. Only 20 relevant results will be fetched by default.");
            }

            //pass the list of ids to fetchMedline to download them. like a id fetcher for mutliple ids
            entryList = fetchMedline(idList);

            return entryList;
        }
    }

    private URL createSearchUrl(String term) throws URISyntaxException, MalformedURLException {
        term = replaceCommaWithAND(term);
        URIBuilder uriBuilder = new URIBuilder("http://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi");
        uriBuilder.addParameter("db", "pubmed");
        uriBuilder.addParameter("sort", "relevance");
        uriBuilder.addParameter("term", term);
        return uriBuilder.build().toURL();
    }

    /**
     * Fetch and parse an medline item from eutils.ncbi.nlm.nih.gov.
     * The E-utilities generate a huge XML file containing all entries for the ids
     *
     * @param ids A list of IDs to search for.
     * @return Will return an empty list on error.
     */
    private List<BibEntry> fetchMedline(List<String> ids) throws FetcherException {
        try {
            //Separate the IDs with a comma to search multiple entries
            URL fetchURL = getURLForID(String.join(",", ids));
            URLConnection data = fetchURL.openConnection();
            ParserResult result = new MedlineImporter().importDatabase(
                    new BufferedReader(new InputStreamReader(data.getInputStream(), StandardCharsets.UTF_8)));
            if (result.hasWarnings()) {
                LOGGER.warn(result.getErrorMessage());
            }
            List<BibEntry> resultList = result.getDatabase().getEntries();
            resultList.forEach(entry -> doPostCleanup(entry));
            return resultList;
        } catch (URISyntaxException | IOException e) {
            throw new FetcherException(e.getLocalizedMessage(), e);
        }
    }

}
