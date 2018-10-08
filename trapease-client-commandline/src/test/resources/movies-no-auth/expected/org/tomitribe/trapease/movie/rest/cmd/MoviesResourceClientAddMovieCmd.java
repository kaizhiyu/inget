package org.tomitribe.trapease.movie.rest.cmd;

import io.airlift.airline.Command;
import io.airlift.airline.Option;
import org.tomitribe.trapease.movie.rest.client.MovieClient;
import org.tomitribe.trapease.movie.rest.client.base.ClientConfiguration;
import org.tomitribe.trapease.movie.rest.cmd.base.TrapeaseCommand;

@Command(name = "add-movie")
public class MoviesResourceClientAddMovieCmd extends TrapeaseCommand {

    @Override
    public void run(
            final ClientConfiguration clientConfiguration) {
        final org.tomitribe.trapease.movie.model.Movie movie = org.tomitribe.trapease.movie.model.Movie.builder().id(id)
                .title(title).director(director).genre(genre).year(year).rating(rating).build();
        System.out.println(new org.apache.johnzon.mapper.MapperBuilder().setPretty(true).build()
                .writeObjectAsString(new MovieClient(clientConfiguration).moviesresourceclient().addMovie(movie)));
    }

    @Option(name = "--id")
    private java.lang.String id;

    @Option(name = "--title")
    private java.lang.String title;

    @Option(name = "--director")
    private java.lang.String director;

    @Option(name = "--genre")
    private java.lang.String genre;

    @Option(name = "--year")
    private int year;

    @Option(name = "--rating")
    private int rating;
}