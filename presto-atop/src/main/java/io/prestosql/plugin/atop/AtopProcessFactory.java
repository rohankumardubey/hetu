/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.atop;

import com.google.common.util.concurrent.SimpleTimeLimiter;
import com.google.common.util.concurrent.TimeLimiter;
import com.google.common.util.concurrent.UncheckedTimeoutException;
import io.airlift.units.Duration;
import io.prestosql.spi.PrestoException;

import javax.annotation.PreDestroy;
import javax.inject.Inject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.plugin.atop.AtopErrorCode.ATOP_CANNOT_START_PROCESS_ERROR;
import static io.prestosql.plugin.atop.AtopErrorCode.ATOP_READ_TIMEOUT;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class AtopProcessFactory
        implements AtopFactory
{
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("YYYYMMdd");
    private final String executablePath;
    private final ZoneId timeZone;
    private final Duration readTimeout;
    private final ExecutorService executor;
    private Pattern pattern = Pattern.compile("[/.a-zA-Z0-9_-]+");

    @Inject
    public AtopProcessFactory(AtopConnectorConfig config, AtopCatalogName catalogName)
    {
        this.executablePath = transToCanonPath(config.getExecutablePath());
        if (!pattern.matcher(this.executablePath).matches()) {
            throw new PrestoException(ATOP_CANNOT_START_PROCESS_ERROR, "Cannot start atop connector for error file path");
        }
        this.timeZone = config.getTimeZoneId();
        this.readTimeout = config.getReadTimeout();
        this.executor = newFixedThreadPool(config.getConcurrentReadersPerNode(), daemonThreadsNamed("atop-" + catalogName + "executable-reader-%s"));
    }

    @Override
    public Atop create(AtopTable table, ZonedDateTime date)
    {
        checkArgument(date.getZone().getRules().equals(timeZone.getRules()), "Split date (%s) is not in the local timezone (%s)", date.getZone(), timeZone);

        ProcessBuilder processBuilder = new ProcessBuilder(executablePath);
        processBuilder.command().add("-P");
        processBuilder.command().add(table.getAtopLabel());
        processBuilder.command().add("-r");
        processBuilder.command().add(DATE_FORMATTER.format(date));
        Process process;
        try {
            process = processBuilder.start();
        }
        catch (IOException e) {
            throw new PrestoException(ATOP_CANNOT_START_PROCESS_ERROR, format("Cannot start %s", processBuilder.command()), e);
        }
        return new AtopProcess(process, readTimeout, executor);
    }

    public String transToCanonPath(String path)
    {
        File file = new File(path);
        try {
            return file.getCanonicalPath();
        }
        catch (IOException e) {
            throw new PrestoException(ATOP_CANNOT_START_PROCESS_ERROR, "Cannot start atop connector for error file path");
        }
    }

    @PreDestroy
    public void shutdown()
    {
        executor.shutdownNow();
    }

    private static final class AtopProcess
            implements Atop
    {
        private final Process process;
        private final BufferedReader underlyingReader;
        private final LineReader reader;
        private String line;

        private AtopProcess(Process process, Duration readTimeout, ExecutorService executor)
        {
            this.process = requireNonNull(process, "process is null");
            underlyingReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            TimeLimiter limiter = SimpleTimeLimiter.create(executor);
            this.reader = limiter.newProxy(underlyingReader::readLine, LineReader.class, readTimeout.toMillis(), MILLISECONDS);
            try {
                // Ignore the first two lines, as they are an event since boot (RESET followed by event line)
                this.reader.readLine();
                this.reader.readLine();
                // Read the first real line
                line = this.reader.readLine();
            }
            catch (IOException e) {
                line = null;
            }
            catch (UncheckedTimeoutException e) {
                throw new PrestoException(ATOP_READ_TIMEOUT, "Timeout reading from atop process");
            }
        }

        @Override
        public boolean hasNext()
        {
            return line != null;
        }

        @Override
        public String next()
        {
            if (line == null) {
                throw new NoSuchElementException();
            }
            String currentLine = line;
            try {
                line = reader.readLine();
            }
            catch (IOException e) {
                line = null;
            }
            catch (UncheckedTimeoutException e) {
                throw new PrestoException(ATOP_READ_TIMEOUT, "Timeout reading from atop process");
            }

            return currentLine;
        }

        @Override
        public void close()
        {
            try {
                underlyingReader.close();
            }
            catch (IOException e) {
                // Ignored
            }
            finally {
                process.destroy();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }

    public interface LineReader
    {
        String readLine()
                throws IOException;
    }
}
