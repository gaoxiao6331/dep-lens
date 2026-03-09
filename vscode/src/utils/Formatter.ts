export const Formatter = {
  formatGithubStar(stars: number): string {
    if (stars >= 1000) {
      return `${Number.parseFloat((stars / 1000).toFixed(1))}k`;
    }
    return stars.toString();
  },
};
