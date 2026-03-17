# Android Companion Overview

## One-line definition
Android Companion is a personal Android-side runtime that exposes safe, machine-readable device capabilities to a broader agent system.

## Core idea
The phone should be an execution surface, not the main reasoning brain.
Desktop/server agents decide *what* to do.
The Android app decides *whether it can do it safely* and *how to invoke the bounded OS capability*.

## What the product is
- A JSON action executor
- A thin UI for testing and inspection
- A capability boundary around Android intents, package state, device info, and update flow
- A foundation for future transport layers like FCM or authenticated remote commands

## What the product is not
- Not a full mobile automation suite
- Not a hidden spyware/control product
- Not an always-on autonomous agent in v0.1
- Not a place to hardcode server business logic

## Initial target user
A technical single-user owner who is comfortable sideloading APKs and granting explicit permissions for personal automation use.
