# Contributing to CityDrop_backend

This repo uses a fork-based workflow: you never push directly to this repo. You fork it, work in your fork, and open a pull request back here. Every PR requires approval from a code owner (`@Zeno-Song` or `@wangjiaxing1233`) before it can be merged.

## One-time setup

```bash
# 1. On GitHub, click "Fork" on Zeno-Song/CityDrop_backend — this creates
#    github.com/<your-username>/CityDrop_backend under your own account

# 2. Clone your own fork (not the original)
git clone git@github.com:<your-username>/CityDrop_backend.git
cd CityDrop_backend

# 3. Add the original repo as "upstream" so you can pull the latest changes
git remote add upstream git@github.com:Zeno-Song/CityDrop_backend.git
```

## Per-task workflow

```bash
# 1. Sync main with upstream before branching, so you're not stale
git checkout main
git fetch upstream
git merge upstream/main

# 2. Create a feature branch
git checkout -b feature/short-description

# 3. Commit as normal
git add <files>
git commit -m "message"

# 4. Push to your OWN fork — you have no write access to upstream, so this is the only option
git push origin feature/short-description
```

## Opening the PR

On GitHub, go to your fork → "Compare & pull request." Set base repository to `Zeno-Song/CityDrop_backend`, base branch `main`, and compare against your feature branch. This is what makes it land on the main repo for review.

## After opening

The PR is blocked from merging until a code owner (`@Zeno-Song` or `@wangjiaxing1233`) approves. If changes are requested, just push more commits to the same branch — the PR updates automatically. You don't merge it yourself; only a code owner can, once approved.
