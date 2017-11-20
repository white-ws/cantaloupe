package edu.illinois.library.cantaloupe.resolver;

import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.ConfigurationException;
import edu.illinois.library.cantaloupe.config.Key;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.image.MediaType;
import edu.illinois.library.cantaloupe.script.DelegateScriptDisabledException;
import edu.illinois.library.cantaloupe.script.ScriptEngine;
import edu.illinois.library.cantaloupe.script.ScriptEngineFactory;
import edu.illinois.library.cantaloupe.util.SystemUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.script.ScriptException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AccessDeniedException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <p>Provides access to source content located on an HTTP(S) server.</p>
 *
 * <h1>Protocol Support</h1>
 *
 * <p>HTTP/1.1, HTTPS/1.1, and HTTP/2 (H2) are supported.</p>
 *
 * <p>Insecure HTTP/2 (H2C) is not supported.</p>
 *
 * <h1>Format Determination</h1>
 *
 * <p>For images with recognized name extensions, the format will be inferred
 * by {@link Format#inferFormat(Identifier)}. For images with unrecognized or
 * missing extensions, the <code>Content-Type</code> header will be checked to
 * determine their format, which will incur an extra <code>HEAD</code> request.
 * It is therefore more efficient to serve images with extensions.</p>
 *
 * <h1>Lookup Strategies</h1>
 *
 * <p>Two distinct lookup strategies are supported, defined by
 * {@link Key#HTTPRESOLVER_LOOKUP_STRATEGY}. BasicLookupStrategy locates
 * images by concatenating a pre-defined URL prefix and/or suffix.
 * ScriptLookupStrategy invokes a delegate method to retrieve a URL (and
 * optional auth info) dynamically.</p>
 *
 * <h1>Authentication Support</h1>
 *
 * <p>HTTP Basic authentication is supported.</p>
 *
 * <ul>
 *     <li>When using BasicLookupStrategy, auth info is set globally in the
 *     {@link Key#HTTPRESOLVER_BASIC_AUTH_USERNAME} and
 *     {@link Key#HTTPRESOLVER_BASIC_AUTH_SECRET} configuration keys.</li>
 *     <li>When using ScriptLookupStrategy, auth info can be returned from a
 *     delegate method.</li>
 * </ul>
 *
 * @see <a href="http://www.eclipse.org/jetty/documentation/current/http-client.html">
 *     Jetty HTTP Client</a>
 */
class HttpResolver extends AbstractResolver implements StreamResolver {

    private static class HTTPStreamSource implements StreamSource {

        private final HttpClient client;
        private final URI uri;

        HTTPStreamSource(HttpClient client, URI uri) {
            this.client = client;
            this.uri = uri;
        }

        @Override
        public ImageInputStream newImageInputStream() throws IOException {
            return ImageIO.createImageInputStream(newInputStream());
        }

        @Override
        public InputStream newInputStream() {
            try {
                InputStreamResponseListener listener =
                        new InputStreamResponseListener();
                client.newRequest(uri).
                        timeout(REQUEST_TIMEOUT, TimeUnit.SECONDS).
                        method(HttpMethod.GET).
                        send(listener);

                // Wait for the response headers to arrive.
                Response response = listener.get(REQUEST_TIMEOUT,
                        TimeUnit.SECONDS);

                if (response.getStatus() == HttpStatus.OK_200) {
                    return listener.getInputStream();
                }
            } catch (Exception e) {
                LOGGER.error(e.getMessage(), e);
            }
            return null;
        }

    }

    static class ResourceInfo {

        private URI uri;
        private String username;
        private String secret;

        ResourceInfo(URI uri) {
            this.uri = uri;
        }

        ResourceInfo(URI uri, String username, String secret) {
            this(uri);
            this.username = username;
            this.secret = secret;
        }

        String getSecret() {
            return secret;
        }

        URI getURI() {
            return uri;
        }

        String getUsername() {
            return username;
        }

        @Override
        public String toString() {
            return getURI() + "";
        }

    }

    private static final Logger LOGGER = LoggerFactory.
            getLogger(HttpResolver.class);

    private static final String GET_URL_DELEGATE_METHOD =
            "HttpResolver::get_url";
    private static final int REQUEST_TIMEOUT = 10;

    private static HttpClient jettyClient;

    private static synchronized HttpClient getHTTPClient(ResourceInfo info) {
        if (jettyClient == null) {
            HttpClientTransport transport;
            if (SystemUtils.isALPNAvailable()) {
                HTTP2Client h2Client = new HTTP2Client();
                transport = new HttpClientTransportOverHTTP2(h2Client);
            } else {
                transport = new HttpClientTransportOverHTTP();
            }

            Configuration config = Configuration.getInstance();
            final boolean trustInvalidCerts = config.getBoolean(
                    Key.HTTPRESOLVER_TRUST_ALL_CERTS, false);
            SslContextFactory sslContextFactory =
                    new SslContextFactory(trustInvalidCerts);

            jettyClient = new HttpClient(transport, sslContextFactory);
            jettyClient.setFollowRedirects(true);

            try {
                jettyClient.start();
            } catch (Exception e) {
                LOGGER.error("getHTTPClient(): {}", e.getMessage());
            }
        }

        // Add Basic auth credentials to the authentication store.
        // https://www.eclipse.org/jetty/documentation/9.4.x/http-client-authentication.html
        if (info.getUsername() != null && info.getSecret() != null) {
            AuthenticationStore auth = jettyClient.getAuthenticationStore();
            auth.addAuthenticationResult(new BasicAuthentication.BasicResult(
                    info.getURI(), info.getUsername(), info.getSecret()));
        }
        return jettyClient;
    }

    @Override
    public StreamSource newStreamSource() throws IOException {
        ResourceInfo info;
        try {
            info = getResourceInfo();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("newStreamSource(): {}", e.getMessage());
            throw new IOException(e.getMessage(), e);
        }

        if (info != null) {
            LOGGER.info("Resolved {} to {}", identifier, info.getURI());

            // Send an HTTP HEAD request to check whether the underlying
            // resource is accessible.
            final HttpClient client = getHTTPClient(info);
            try {
                InputStreamResponseListener listener =
                        new InputStreamResponseListener();
                client.newRequest(info.getURI()).
                        timeout(REQUEST_TIMEOUT, TimeUnit.SECONDS).
                        method(HttpMethod.HEAD).send(listener);

                // Wait for the response headers to arrive.
                Response response = listener.get(REQUEST_TIMEOUT,
                        TimeUnit.SECONDS);

                if (response.getStatus() >= HttpStatus.BAD_REQUEST_400) {
                    final String statusLine = "HTTP " + response.getStatus() +
                            ": " + response.getReason();

                    if (response.getStatus() == HttpStatus.NOT_FOUND_404
                            || response.getStatus() == HttpStatus.GONE_410) {
                        throw new FileNotFoundException(statusLine);
                    } else if (response.getStatus() == HttpStatus.UNAUTHORIZED_401) {
                        throw new AccessDeniedException(statusLine);
                    } else {
                        throw new IOException(statusLine);
                    }
                }
                return new HTTPStreamSource(client, info.getURI());
            } catch (ExecutionException e) {
                throw new AccessDeniedException(info.getURI().toString());
            } catch (InterruptedException | TimeoutException e) {
                LOGGER.error(e.getMessage(), e);
                throw new IOException(e.getMessage(), e);
            }
        }
        return null;
    }

    @Override
    public Format getSourceFormat() throws IOException {
        if (sourceFormat == null) {
            sourceFormat = Format.inferFormat(identifier);
            if (Format.UNKNOWN.equals(sourceFormat)) {
                sourceFormat = inferSourceFormatFromContentTypeHeader();
            }
            // This will throw a variety of exceptions if the source is
            // inaccessible, not found, etc., in order to comply with the
            // StreamResolver contract.
            newStreamSource();
        }
        return sourceFormat;
    }

    ResourceInfo getResourceInfo() throws Exception {
        final Configuration config = Configuration.getInstance();
        switch (config.getString(Key.HTTPRESOLVER_LOOKUP_STRATEGY)) {
            case "BasicLookupStrategy":
                return getResourceInfoUsingBasicStrategy();
            case "ScriptLookupStrategy":
                return getResourceInfoUsingScriptStrategy();
            default:
                throw new ConfigurationException(Key.HTTPRESOLVER_LOOKUP_STRATEGY +
                        " is invalid or not set");
        }
    }

    private ResourceInfo getResourceInfoUsingBasicStrategy()
            throws ConfigurationException {
        final Configuration config = Configuration.getInstance();
        final String prefix = config.getString(Key.HTTPRESOLVER_URL_PREFIX, "");
        final String suffix = config.getString(Key.HTTPRESOLVER_URL_SUFFIX, "");
        try {
            return new ResourceInfo(
                    new URI(prefix + identifier.toString() + suffix),
                    config.getString(Key.HTTPRESOLVER_BASIC_AUTH_USERNAME),
                    config.getString(Key.HTTPRESOLVER_BASIC_AUTH_SECRET));
        } catch (URISyntaxException e) {
            throw new ConfigurationException(e.getMessage());
        }
    }

    /**
     * @throws FileNotFoundException If the remote resource was not found.
     * @throws URISyntaxException    If {@link #GET_URL_DELEGATE_METHOD}
     *                               returns an invalid URI.
     * @throws IOException
     * @throws ScriptException       If the script fails to execute.
     * @throws DelegateScriptDisabledException
     */
    private ResourceInfo getResourceInfoUsingScriptStrategy()
            throws URISyntaxException, IOException, ScriptException,
            DelegateScriptDisabledException {
        final ScriptEngine engine = ScriptEngineFactory.getScriptEngine();
        final Object result = engine.invoke(GET_URL_DELEGATE_METHOD,
                identifier.toString(), context.asMap());
        if (result == null) {
            throw new FileNotFoundException(GET_URL_DELEGATE_METHOD +
                    " returned nil for " + identifier);
        }
        // The return value may be a string URI, or a hash with "uri",
        // "username", and "secret" keys.
        if (result instanceof String) {
            return new ResourceInfo(new URI((String) result));
        } else {
            @SuppressWarnings("unchecked")
            final Map<String,String> info = (Map<String,String>) result;
            final String uri = info.get("uri");
            final String username = info.get("username");
            final String secret = info.get("secret");
            return new ResourceInfo(new URI(uri), username, secret);
        }
    }

    /**
     * Issues an HTTP HEAD request and checks the response
     * <code>Content-Type</code> header to determine the source format.
     *
     * @return Inferred source format, or {@link Format#UNKNOWN} if unknown.
     */
    private Format inferSourceFormatFromContentTypeHeader() {
        Format format = Format.UNKNOWN;
        try {
            final ResourceInfo info = getResourceInfo();
            final HttpClient client = getHTTPClient(info);
            InputStreamResponseListener listener =
                    new InputStreamResponseListener();
            client.newRequest(info.getURI()).
                    timeout(REQUEST_TIMEOUT, TimeUnit.SECONDS).
                    method(HttpMethod.HEAD).
                    send(listener);

            // Wait for the response headers to arrive.
            Response response = listener.get(REQUEST_TIMEOUT,
                    TimeUnit.SECONDS);

            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                HttpField field = response.getHeaders().getField("Content-Type");
                if (field != null && field.getValue() != null) {
                    format = new MediaType(field.getValue()).toFormat();
                    if (Format.UNKNOWN.equals(format)) {
                        LOGGER.warn("Unrecognized Content-Type header value for HEAD {}",
                                info.getURI());
                    }
                } else {
                    LOGGER.warn("No Content-Type header for HEAD {}",
                            info.getURI());
                }
            } else {
                LOGGER.warn("HEAD returned status {} for {}",
                        response.getStatus(), info.getURI());
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
        return format;
    }

}
