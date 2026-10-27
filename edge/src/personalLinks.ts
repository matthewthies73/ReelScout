/**
 * Six Degrees of Matthew Thies, served at GET /api/personal-links.
 *
 * Matthew made Reel Scout and is an unknown in Hollywood, but he has met a few people who
 * aren't. Everyone listed here is someone he has met in person, which puts them one degree
 * from him: a link no film credit records, so TMDB can't know about it. The app's
 * ConnectionFinder (shared/.../data/ConnectionFinder.kt) uses these links only when he's
 * one of the two people in a connection question.
 *
 * To add someone:
 * 1. Find their TMDB person id: the number in their themoviedb.org/person/<id>-<name> URL.
 * 2. Add an entry to `links`. `how` is shown in answers, publicly, as written.
 * 3. Push to main. deploy-worker.yml redeploys the relay, and the apps pick it up the next
 *    time they start, with no app release. `npm test` checks the entries first.
 */

export interface PersonalLinks {
  person: { tmdb_id: number; name: string };
  links: { tmdb_id: number; name: string; how: string }[];
}

// snake_case, as the app's JSON reader expects.
export const PERSONAL_LINKS: PersonalLinks = {
  person: { tmdb_id: 1740657, name: "Matthew Thies" },
  links: [
    { tmdb_id: 2390, name: "LeVar Burton", how: "met through my ex-wife Elissa Prager during cast parties and screenings for 'Reach For Me'" },
    { tmdb_id: 11782, name: "Adrienne Barbeau", how: "met at screening for 'Reach For Me'"}
  ],
};
