#!/usr/bin/env python3
"""Synthetic corpus generator for the Discovery Hub legal-discovery platform.

Generates a deterministic, realistic-looking corpus of fictional **retail and
corporate banking** communications (EMAIL and CHAT) with attachments, writes
it to disk and submits it to the Discovery Hub ingestion API
(``POST {api-url}/api/ingestion/messages``).

The corpus covers the message types a bank's legal-discovery platform would
actually hold: credit underwriting, AML and KYC alerts, treasury and
liquidity, regulatory reporting, payment and settlement incidents, trade
finance, collections and recoveries, fraud and chargeback disputes, internal
audit findings, and wealth-management suitability reviews.

All people, institutions, counterparties, account numbers and domains
produced by this script are fictional. The email domain is
``example-bank.test`` which is reserved by RFC 2606 and can never resolve to
a real mailbox. No real customer, account or transaction data is used.

Requires Python 3.11+ and ``requests``. ``boto3`` is only needed for the
optional ``--upload-to-s3`` archival step.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import concurrent.futures
import dataclasses
import datetime as dt
import hashlib
import io
import json
import os
import random
import struct
import sys
import threading
import time
import zipfile
import zlib
from pathlib import Path
from typing import Any, Callable, Sequence

import requests

# --------------------------------------------------------------------------
# Constants and vocabularies
# --------------------------------------------------------------------------

EMAIL_DOMAIN = "example-bank.test"
COMPANY_NAME = "Example Bank"
INGESTION_PATH = "/api/ingestion/messages"
HTTP_ACCEPTED = 202

DEPARTMENTS: tuple[str, ...] = (
    "Credit Risk",
    "Corporate Banking",
    "Retail Banking",
    "Treasury",
    "Financial Crime Compliance",
    "Regulatory Reporting",
    "Payments Operations",
    "Trade Finance",
    "Collections and Recoveries",
    "Wealth Management",
    "Internal Audit",
    "Legal",
)

JOB_TITLES: dict[str, tuple[str, ...]] = {
    "Credit Risk": (
        "Chief Credit Officer", "Head of Credit Risk", "Senior Credit Analyst",
        "Credit Underwriter", "Portfolio Risk Analyst",
    ),
    "Corporate Banking": (
        "Head of Corporate Banking", "Relationship Director",
        "Senior Relationship Manager", "Coverage Analyst",
    ),
    "Retail Banking": (
        "Head of Retail Banking", "Regional Branch Manager",
        "Branch Operations Manager", "Personal Banking Officer",
    ),
    "Treasury": (
        "Group Treasurer", "Head of ALM", "Liquidity Manager",
        "Money Markets Dealer", "Balance Sheet Analyst",
    ),
    "Financial Crime Compliance": (
        "Chief Compliance Officer", "Head of Financial Crime", "AML Manager",
        "Sanctions Analyst", "KYC Reviewer", "Transaction Monitoring Analyst",
    ),
    "Regulatory Reporting": (
        "Head of Regulatory Reporting", "Basel Reporting Manager",
        "Prudential Returns Analyst", "Regulatory Data Analyst",
    ),
    "Payments Operations": (
        "Head of Payments", "Settlements Manager", "Nostro Reconciliation Lead",
        "Payments Investigations Officer",
    ),
    "Trade Finance": (
        "Head of Trade Finance", "Documentary Credits Manager",
        "Trade Operations Officer", "Guarantees Specialist",
    ),
    "Collections and Recoveries": (
        "Head of Collections", "Recoveries Manager", "NPA Resolution Lead",
        "Collections Analyst",
    ),
    "Wealth Management": (
        "Head of Private Banking", "Senior Wealth Adviser",
        "Portfolio Manager", "Investment Suitability Analyst",
    ),
    "Internal Audit": (
        "Chief Internal Auditor", "Audit Manager", "Senior Auditor",
        "Controls Testing Lead",
    ),
    "Legal": (
        "General Counsel", "Senior Counsel", "Banking Counsel",
        "Litigation Manager", "Paralegal",
    ),
}

FIRST_NAMES: tuple[str, ...] = (
    "Aditi", "Rohan", "Meera", "Kabir", "Ananya", "Vikram", "Priya", "Arjun",
    "Nisha", "Farhan", "Tara", "Dev", "Ishani", "Karan", "Leela", "Omar",
    "Riya", "Samir", "Uma", "Yash", "Zoya", "Aaron", "Beatrice", "Callum",
    "Dana", "Elena", "Felix", "Greta", "Hugo", "Imogen", "Jonas", "Klara",
    "Lucia", "Mateo", "Nadia", "Oscar", "Petra", "Quinn", "Rosa", "Soren",
)

LAST_NAMES: tuple[str, ...] = (
    "Sharma", "Patel", "Iyer", "Nair", "Desai", "Kulkarni", "Banerjee", "Reddy",
    "Chowdhury", "Menon", "Ahluwalia", "Bhattacharya", "Fernandes", "Gokhale",
    "Rahman", "Sandoval", "Okonkwo", "Lindqvist", "Moreau", "Vasquez",
    "Kowalski", "Nakamura", "Bergstrom", "Delgado", "Petrov", "Halvorsen",
    "Adeyemi", "Novak", "Rossi", "Marchetti", "Kaminski", "Larsen",
)

# Internal programme / deal code names.
PROJECT_CODE_NAMES: tuple[str, ...] = (
    "Atlas", "Borealis", "Cascade", "Driftwood", "Everest", "Foxglove",
    "Granite", "Harbour", "Ironwood", "Juniper", "Kestrel", "Lantern",
    "Meridian", "Northwind", "Obsidian", "Pinnacle", "Quarry", "Redwood",
    "Sandpiper", "Trailhead", "Umbra", "Vantage", "Wavelength", "Zephyr",
)

# Fictional corporate borrowers and counterparties.
VENDORS: tuple[str, ...] = (
    "Northgate Steel Works", "Bluefin Seafoods", "Corvus Logistics",
    "Delta Ridge Infrastructure", "Everline Pharmaceuticals",
    "Fairmount Real Estate Holdings", "Granary Agro Exports",
    "Halcyon Media Group", "Ironvale Cement", "Kestrel Airlines",
    "Lakeshore Textiles", "Meridian Power Utilities",
    "Sandpiper Hospitality", "Trailhead Automotive Components",
)

# Fictional correspondent and counterparty banks.
COUNTERPARTY_BANKS: tuple[str, ...] = (
    "Haldane Commercial Bank", "Rivermark Bank", "Sundale Trust Bank",
    "Portside Mercantile Bank", "Ashgrove National Bank",
    "Westmoor Clearing Bank",
)

REGIONS: tuple[str, ...] = (
    "APAC", "EMEA", "North America", "LATAM", "India South", "Nordics",
)

# Banking business lines and portfolios.
PORTFOLIOS: tuple[str, ...] = (
    "SME lending", "large corporate", "commercial real estate",
    "unsecured personal loans", "mortgage", "credit cards",
    "auto loans", "working capital", "project finance",
)

REGULATORS: tuple[str, ...] = (
    "the central bank", "the prudential regulator", "the conduct regulator",
    "the financial intelligence unit",
)

RETURNS: tuple[str, ...] = (
    "LCR", "NSFR", "Basel III Pillar 3 disclosure", "capital adequacy return",
    "large exposures return", "IFRS 9 ECL model output", "liquidity gap report",
)

RISK_RATINGS: tuple[str, ...] = (
    "BBB-", "BB+", "BB", "BB-", "B+", "internal grade 5", "internal grade 6",
    "internal grade 7", "watchlist",
)

PAYMENT_RAILS: tuple[str, ...] = (
    "RTGS", "NEFT", "SWIFT MT103", "SWIFT MT202", "ACH", "SEPA credit transfer",
    "UPI", "card settlement",
)


@dataclasses.dataclass(frozen=True)
class Theme:
    """A banking conversation theme with subject and body templates.

    Email bodies are assembled as greeting, opener, background, several
    details, a bulleted block, a risk paragraph, an action and a signature,
    which produces multi-paragraph messages of realistic length rather than
    one-liners.
    """

    key: str
    subjects: tuple[str, ...]
    openers: tuple[str, ...]
    background: tuple[str, ...]
    details: tuple[str, ...]
    bullets: tuple[str, ...]
    risks: tuple[str, ...]
    actions: tuple[str, ...]
    chat_lines: tuple[str, ...]


THEMES: tuple[Theme, ...] = (
    Theme(
        key="credit",
        subjects=(
            "Credit paper: {vendor} - {amount} facility",
            "{vendor}: renewal of working capital limits",
            "Sanction conditions - {vendor} term loan",
            "Rating migration: {vendor} to {rating}",
            "Credit committee pack - {portfolio} portfolio",
            "Security perfection pending: {vendor}",
        ),
        openers=(
            "Attaching the credit paper for the {vendor} {amount} facility ahead of the credit committee on {weekday}.",
            "We have completed the annual review of the {vendor} exposure and are recommending a renewal of the working capital limits with tighter covenants.",
            "The internal rating for {vendor} has migrated to {rating} following the {quarter} results, so the facility needs to be re-presented to committee.",
            "Ahead of the {portfolio} portfolio review, here is where the {vendor} relationship stands and what we are recommending.",
        ),
        background=(
            "{vendor} has banked with us for {years} years across the {portfolio} book, and total group exposure now stands at {exposure} including the undrawn portion of the revolver.",
            "The relationship began as a plain vanilla working capital facility and has since grown to include a term loan, a {rail} collection arrangement and two guarantees issued through Trade Finance.",
            "Our current sanction dates from the previous cycle, when the borrower was rated {rating} and the sector outlook for {region} was materially more benign than it is today.",
            "This is a {portfolio} account managed out of {region}, with a group exposure of {exposure} against sanctioned limits that were last reviewed in {quarter}.",
        ),
        details=(
            "Leverage has moved from 2.4x to {ratio}x over the last four quarters, driven mostly by a debt-funded capacity expansion that is running roughly {days} days behind schedule.",
            "Interest coverage remains adequate at {ratio}x, but the headroom is thinner than the covenant threshold of 2.0x that we agreed at sanction.",
            "The security package covers a first charge over current assets plus a personal guarantee, giving us a collateral coverage ratio of about {ratio}x on the drawn amount.",
            "Cash flows are seasonal and the peak working capital requirement falls in {quarter}, which is when the borrower has historically breached the drawing power calculation.",
            "We have priced the facility at {bps} bps over the benchmark, which is {percent}% below where we are pricing comparable {rating} names in the same sector.",
            "The stock audit flagged a {percent}% shortfall in the declared inventory position, and the borrower has not yet reconciled the difference.",
            "Two of the sanction conditions from the previous cycle remain open, including the creation of a charge on the {region} plant, which is now {days} days overdue.",
        ),
        bullets=(
            "Sanctioned limit: {amount}, current outstanding {exposure}",
            "Internal rating: {rating}, previous cycle one notch higher",
            "Leverage {ratio}x against a covenant of 3.0x",
            "Collateral coverage {ratio}x on drawn balances",
            "Pricing {bps} bps over benchmark, reset annually",
            "Security perfection outstanding for {days} days",
            "Stock audit variance of {percent}% unreconciled",
            "Account conduct satisfactory, no cheque returns in {quarter}",
        ),
        risks=(
            "If the capacity expansion slips another quarter, the borrower will likely breach the leverage covenant at the {quarter} testing date and we would need to decide between a waiver and a repricing.",
            "The main downside risk is concentration: this single name represents a meaningful share of our {portfolio} exposure in {region}, so a downgrade would have a disproportionate effect on portfolio risk-weighted assets.",
            "Given the rating migration to {rating}, expected credit loss under IFRS 9 moves this account towards stage 2, which would increase the provision by approximately {amount}.",
            "We should be clear with committee that the security shortfall is the binding constraint here, not the cash flow position.",
        ),
        actions=(
            "Could you confirm whether Credit Risk is comfortable with the recommended structure before I circulate the pack on {weekday}?",
            "Please review the covenant package and let me know if you want the leverage test tightened before this goes to committee.",
            "I need your sign-off on the pricing exception by {weekday}, otherwise we present at the standard {rating} spread.",
            "Can you confirm the security perfection timeline with the borrower and revert by {weekday}?",
        ),
        chat_lines=(
            "did the {vendor} credit paper get circulated for {weekday}'s committee? I still don't see it in the folder",
            "{vendor} has migrated to {rating} after the {quarter} numbers, we'll need to re-present the facility",
            "stock audit on {vendor} shows a {percent}% inventory shortfall, drawing power needs recalculating",
            "security perfection for {vendor} is {days} days overdue, that's going to show up as an audit finding",
            "committee approved the {amount} limit but wants leverage tested quarterly instead of annually",
            "can you pull the group exposure for {vendor}? I'm getting {exposure} including undrawn but the system shows less",
        ),
    ),
    Theme(
        key="aml",
        subjects=(
            "AML alert {ref} - {vendor}",
            "Enhanced due diligence: {vendor}",
            "Suspicious activity report - draft for review",
            "Sanctions screening hit: {rail} payment {ref}",
            "KYC remediation backlog - {region}",
            "Transaction monitoring tuning - {portfolio}",
        ),
        openers=(
            "Alert {ref} was generated on the {vendor} account for a series of {count} structured cash deposits just below the reporting threshold.",
            "We are escalating the {vendor} relationship for enhanced due diligence following adverse media identified during the periodic review.",
            "A {rail} payment of {amount} for {vendor} has been held after a possible sanctions match on an intermediary party.",
            "The KYC remediation backlog for {region} now stands at {count} files, and {percent}% of those are in the high-risk category.",
        ),
        background=(
            "The account was onboarded {years} years ago as a low-risk {portfolio} customer, and the risk rating has not been refreshed since the beneficial ownership structure changed.",
            "Transaction monitoring generated {count} alerts on this relationship over the last twelve months, of which all but one were closed as false positives.",
            "The customer is a trading company with declared turnover well below the volumes we are now seeing through the {rail} channel.",
            "This relationship sits in the {portfolio} book and is serviced out of {region}, where we have already identified control gaps in the periodic review process.",
        ),
        details=(
            "The deposits were made across {count} branches over {days} days, each between {amount} and the reporting threshold, which is a classic structuring pattern.",
            "Funds were consolidated and remitted onward to a single beneficiary account at {bank}, with no clear commercial rationale in the payment narrative.",
            "The declared source of funds is trade receivables, but the counterparty invoices provided do not reconcile to the amounts credited.",
            "Beneficial ownership has changed twice since onboarding and the current ultimate beneficial owner is not documented in the file.",
            "The screening hit relates to a name and date-of-birth partial match; the sanctions analyst has requested identity documents to discount it.",
            "We are seeing round-sum transfers of {amount} in and out within {minutes} minutes, leaving negligible balances at day end.",
            "Of the {count} open remediation files, {percent}% are missing a current proof of address and cannot be closed without customer contact.",
        ),
        bullets=(
            "Alert reference: {ref}, generated by rule set {count}",
            "Aggregate value under review: {amount} across {days} days",
            "Customer risk rating: high, last refreshed {years} years ago",
            "Onward beneficiary: account at {bank}",
            "Source of funds documentation incomplete",
            "Beneficial ownership not evidenced since restructuring",
            "Screening match: partial, pending identity verification",
            "Regulatory reporting deadline: {date}",
        ),
        risks=(
            "If we cannot evidence the source of funds we should be prepared to file with {regulator} and consider exiting the relationship, and Legal will need to advise on tipping-off risk.",
            "The pattern is consistent enough that leaving the account operational while we investigate carries real regulatory exposure for the bank.",
            "Holding the payment beyond {days} days will breach our service commitment to the customer, so we need the screening question resolved quickly either way.",
            "The remediation backlog is the bigger systemic issue: {regulator} asked for evidence of periodic review at the last inspection and we could not fully demonstrate it.",
        ),
        actions=(
            "Please review the draft report and confirm whether you agree with filing before the {date} deadline.",
            "Can you confirm by {weekday} whether we are releasing or rejecting the held payment?",
            "I need Financial Crime Compliance to sign off the risk rating change before we notify the relationship manager.",
            "Please do not discuss this with the customer until Legal has advised on disclosure.",
        ),
        chat_lines=(
            "alert {ref} on the {vendor} account looks like structuring, {count} deposits just under the threshold",
            "the {rail} payment for {amount} is still on hold, sanctions team wants ID documents before releasing",
            "we're at {count} open KYC files for {region}, {percent}% are high risk. this will come up at the inspection",
            "please don't mention the review to the customer, legal is still looking at tipping off",
            "sar draft is ready for review, filing deadline is {date} so I need comments today",
            "beneficial ownership on {vendor} changed twice and nobody updated the file",
        ),
    ),
    Theme(
        key="treasury",
        subjects=(
            "Liquidity position - {date}",
            "{regreturn} breach - {quarter}",
            "ALCO pack: interest rate sensitivity",
            "Funding plan for {quarter}",
            "Deposit concentration - {region}",
            "Intraday liquidity buffer utilisation",
        ),
        openers=(
            "The {regreturn} closed at {percent}% this morning, which is inside the board limit but {bps} bps below where we were at the start of the week.",
            "We breached the internal {regreturn} trigger on {date} and I want to walk through the drivers before ALCO on {weekday}.",
            "Attaching the ALCO pack covering interest rate sensitivity and the funding plan for {quarter}.",
            "Deposit concentration in {region} has increased again; the top {count} depositors now represent {percent}% of the funding base.",
        ),
        background=(
            "We have been running the liquidity buffer close to the internal floor since the {portfolio} book started growing faster than deposit mobilisation.",
            "The balance sheet is structurally asset-sensitive: roughly {percent}% of the loan book reprices within ninety days while a large share of term deposits is locked for a year.",
            "Wholesale funding has been more expensive since the last policy move, and we have been rolling {amount} of short-dated money market borrowing rather than terming it out.",
            "This is the third consecutive month where the intraday buffer has been drawn down to fund {rail} settlement obligations before the afternoon inflows arrive.",
        ),
        details=(
            "The main driver was a single corporate outflow of {amount} that we knew about but which landed {days} days earlier than the customer had indicated.",
            "High-quality liquid assets stand at {exposure}, of which about {percent}% is central bank eligible and can be monetised the same day.",
            "A 100 bps parallel upward shift improves net interest income by roughly {amount} over twelve months but reduces economic value of equity by a comparable amount.",
            "We are paying {bps} bps over the benchmark for three-month wholesale money, which is {percent}% wider than the same period last year.",
            "The behavioural assumptions on non-maturity deposits have not been re-estimated since the last rate cycle and are probably too optimistic.",
            "Net stable funding sits at {percent}%, and the {portfolio} growth pipeline for {quarter} would push it towards the regulatory floor without additional term funding.",
            "Intraday peak utilisation reached {percent}% of the committed buffer on {date}, mostly driven by {rail} obligations settling before inflows.",
        ),
        bullets=(
            "{regreturn}: {percent}%, board limit set {bps} bps lower",
            "HQLA stock: {exposure}, {percent}% central bank eligible",
            "Largest single outflow in period: {amount}",
            "Wholesale funding cost: {bps} bps over benchmark",
            "Top {count} depositors: {percent}% of total deposits",
            "Repricing gap inside 90 days: {percent}% of assets",
            "Intraday buffer peak utilisation: {percent}%",
            "Term funding requirement for {quarter}: {amount}",
        ),
        risks=(
            "If we fund {quarter} loan growth entirely from short-dated wholesale money, the maturity mismatch widens and we would struggle to defend the {regreturn} position to {regulator}.",
            "The concentration risk is the one I would highlight to ALCO: losing the largest two depositors would consume most of the surplus buffer in a single day.",
            "Our deposit behavioural assumptions are the weakest part of the model, and if they are wrong the reported ratio overstates our true resilience.",
            "A breach that persists across a reporting date becomes a disclosure item, which is a materially different conversation with {regulator}.",
        ),
        actions=(
            "Please review the funding plan and confirm you are comfortable with the term issuance assumption before ALCO on {weekday}.",
            "Can Treasury confirm the corrected {regreturn} figure so Regulatory Reporting can refile?",
            "I would like a decision at ALCO on whether we term out {amount} of the wholesale book this quarter.",
            "Please re-run the sensitivity with updated deposit assumptions and send it across by {weekday}.",
        ),
        chat_lines=(
            "{regreturn} is at {percent}% this morning, we're inside the limit but only just",
            "that {amount} corporate outflow landed {days} days early and blew through the intraday buffer",
            "alco moved to {weekday}, we need the rate sensitivity numbers before then",
            "top {count} depositors are now {percent}% of funding, concentration is getting uncomfortable",
            "we're paying {bps} over benchmark for 3 month money, terming out is getting expensive",
            "deposit behavioural assumptions haven't been refreshed since the last cycle, the model is optimistic",
        ),
    ),
    Theme(
        key="regulatory",
        subjects=(
            "{regreturn} submission - {quarter}",
            "{regulator} inspection findings",
            "Data quality issue in {regreturn}",
            "Capital adequacy - {quarter} numbers",
            "Refiling required: {regreturn}",
            "Large exposures breach - {vendor}",
        ),
        openers=(
            "The {quarter} {regreturn} submission is due on {date} and we have {count} open data quality issues that need resolving first.",
            "{regulator} has issued draft findings following the inspection, and there are {count} points that need a management response.",
            "We have identified a data quality issue in the {regreturn} that affects the reported figure by approximately {percent}%.",
            "The large exposures return shows the {vendor} group above the regulatory ceiling once we consolidate connected counterparties.",
        ),
        background=(
            "The return is assembled from three source systems, and the reconciliation between the core banking extract and the risk data mart has never been fully automated.",
            "We have submitted this return {count} times since the reporting change, and each cycle has needed at least one manual adjustment to get the numbers to tie.",
            "{regulator} flagged the same control weakness at the previous inspection, and our remediation commitment had a target date of {date}.",
            "Capital adequacy has been trending down as the {portfolio} book grows, and the {quarter} numbers include the first full impact of the revised risk weights.",
        ),
        details=(
            "The root cause is that {count} facilities were classified against the wrong exposure class, which understates risk-weighted assets by around {amount}.",
            "Connected counterparty aggregation is the weak point: {vendor} and two subsidiaries are onboarded as separate customers with no group linkage in the system.",
            "Common equity tier 1 stands at {percent}%, which is above the requirement but only {bps} bps above our own board-approved buffer.",
            "The IFRS 9 expected credit loss model output moved by {percent}% quarter on quarter, driven mostly by the {rating} band migrations in the {portfolio} book.",
            "Our previous submission used a stale collateral valuation, so the loss-given-default input was understated for {count} secured facilities.",
            "The finding on access recertification is valid: the last cycle was incomplete for {region} and we cannot evidence review of {count} privileged accounts.",
            "Correcting the classification means refiling {quarter} and restating the comparative, which we need to disclose in the submission cover note.",
        ),
        bullets=(
            "Submission deadline: {date}, {days} working days remaining",
            "Open data quality issues: {count}",
            "Estimated impact on reported figure: {percent}%",
            "CET1 ratio: {percent}%, {bps} bps above board buffer",
            "Facilities misclassified by exposure class: {count}",
            "Large exposure headroom for {vendor}: exhausted",
            "Prior inspection finding: repeat, target date {date}",
            "Refiling and restatement of comparatives required",
        ),
        risks=(
            "A repeat finding is much harder to defend than a first occurrence, and {regulator} will reasonably ask why the previous remediation did not work.",
            "If we submit on the current numbers and correct later, we are choosing a refiling over a late submission; both are reportable but the refiling also affects the comparative.",
            "The large exposures position is the most urgent item because it is a hard limit rather than a reporting preference, and a breach is immediately notifiable.",
            "Understating risk-weighted assets by {amount} would overstate the capital ratio, which is exactly the kind of error that attracts a supervisory add-on.",
        ),
        actions=(
            "Please confirm by {weekday} whether we submit with the adjustment or seek an extension from {regulator}.",
            "I need the {department} response to findings {count} and the remediation owner named before we reply.",
            "Can you validate the corrected exposure classes so we can quantify the restatement?",
            "Please review the cover note before it goes to the regulator; the disclosure wording matters here.",
        ),
        chat_lines=(
            "{regreturn} is due {date} and we still have {count} data quality issues open",
            "the classification error understates rwa by about {amount}, we'll have to refile {quarter}",
            "{regulator} has raised the same finding as last inspection, that's not a good look",
            "{vendor} group is over the large exposure ceiling once you consolidate the subsidiaries",
            "cet1 came in at {percent}%, only {bps} bps above the board buffer",
            "who owns the remediation for the access recert finding? it needs a name and a date today",
        ),
    ),
    Theme(
        key="payments",
        subjects=(
            "Settlement break {ref} - {bank}",
            "Sev {sev}: {rail} outage",
            "Nostro reconciliation - {count} unmatched items",
            "Failed {rail} batch on {date}",
            "Payment investigation {ref} - {amount}",
            "Cut-off breach: {rail} on {date}",
        ),
        openers=(
            "We have a settlement break of {amount} against our nostro with {bank}, reference {ref}, dating back to the {date} value date.",
            "We had a Sev {sev} incident on the {rail} channel on {date} lasting approximately {minutes} minutes, affecting {count} customer payments.",
            "The nostro reconciliation for {region} has {count} unmatched items, of which {count} are over {days} days old.",
            "A {rail} batch failed part way through processing on {date} and we need to confirm which instructions were actually settled.",
        ),
        background=(
            "This nostro has had a persistent matching problem since the correspondent changed their statement format, and we have been reconciling manually ever since.",
            "The {rail} channel handles the bulk of our corporate payment volume, so an outage during the settlement window has immediate customer impact.",
            "We process the batch close to the network cut-off, which leaves very little room to recover if anything fails in the first attempt.",
            "The investigations queue has been growing since the volume increase, and average time to resolution is now around {days} days.",
        ),
        details=(
            "Root cause was a configuration change applied outside the normal release window, and there was no tested rollback plan in place.",
            "Of the {count} affected instructions, {count} were retried successfully and the remainder were returned to customers with an explanatory advice.",
            "Detection took {minutes} minutes because the alert threshold on queue depth was raised during the last tuning exercise and never reset.",
            "The break appears to be a duplicate debit: the correspondent has processed the {rail} instruction twice for {amount} on the same value date.",
            "We missed the network cut-off by {minutes} minutes, so {count} payments carried over to the next value date and customers are claiming interest compensation.",
            "The unmatched items are mostly charges applied by {bank} that were never booked in our ledger, totalling around {amount}.",
            "Two payments were released to the wrong beneficiary because of a stale mandate, and we are attempting recall through {bank}.",
        ),
        bullets=(
            "Incident severity: Sev {sev}, duration {minutes} minutes",
            "Customer payments affected: {count}",
            "Value at risk: {amount}",
            "Break reference {ref}, value date {date}",
            "Unmatched nostro items: {count}, oldest {days} days",
            "Recall requested through {bank}",
            "Cut-off missed by {minutes} minutes",
            "Compensation claims received: {count}",
        ),
        risks=(
            "Aged nostro breaks are a standing audit issue, and anything over ninety days will need to be provided for, which brings a direct profit and loss impact.",
            "If we cannot recover the duplicate debit from {bank}, the loss sits with us and we will need to book it as an operational loss event.",
            "The bigger concern is control rather than the individual break: a change applied without a rollback plan will fail the change management test at the next audit.",
            "Customer compensation is manageable, but a second cut-off breach in the same quarter would need to be reported as a conduct issue.",
        ),
        actions=(
            "Please confirm the timeline for accuracy before we share the incident summary with the affected customers.",
            "Can you chase {bank} on the recall and let me know the outcome by {weekday}?",
            "I need an owner for each remediation item by {weekday}, particularly the alert threshold reset.",
            "Please clear the aged items over {days} days before the month-end reconciliation sign-off.",
        ),
        chat_lines=(
            "sev {sev} on the {rail} channel, we're on the bridge now. about {count} payments stuck",
            "mitigated after {minutes} mins, root cause looks like a config change with no rollback",
            "break {ref} with {bank} is a duplicate debit for {amount}, chasing the recall",
            "nostro rec has {count} unmatched items, {days} days old. audit will pick this up",
            "we missed the {rail} cut-off by {minutes} minutes, {count} payments rolled to next value date",
            "customer is claiming interest compensation on the delayed payment, routing to investigations",
        ),
    ),
    Theme(
        key="collections",
        subjects=(
            "NPA classification: {vendor}",
            "Recovery strategy - {vendor} {amount}",
            "{portfolio} delinquency - {quarter}",
            "One-time settlement proposal: {vendor}",
            "SARFAESI notice - {vendor}",
            "Restructuring request - {vendor}",
        ),
        openers=(
            "The {vendor} account has crossed {dpd} days past due and will be classified as non-performing at the {quarter} cut-off unless there is a recovery.",
            "We need to agree a recovery strategy for the {vendor} exposure of {amount}, where all normal collection efforts have now been exhausted.",
            "Delinquency in the {portfolio} book has risen to {percent}% in {quarter}, concentrated in the {region} branches.",
            "{vendor} has proposed a one-time settlement of {amount} against an outstanding of {exposure}, which would mean a significant haircut.",
        ),
        background=(
            "This account has been on the watchlist for two quarters, having been restructured once already when the borrower cited a working capital squeeze.",
            "The borrower stopped servicing interest after the {region} operations were suspended, and the promoters have been largely uncontactable since.",
            "Collections has attempted contact {count} times over {days} days through calls, branch visits and a formal demand notice.",
            "The {portfolio} portfolio has been growing quickly in {region}, and the early delinquency numbers suggest the underwriting standards were loosened during that push.",
        ),
        details=(
            "Outstanding principal is {exposure} with accrued unpaid interest of approximately {amount}, and the last credit into the account was {days} days ago.",
            "Security consists of a first charge over the {region} property, most recently valued at {amount}, though that valuation is now {years} years old.",
            "The proposed settlement of {amount} represents a recovery of about {percent}% of book value, which compares favourably with our experience on similar {rating} cases.",
            "Enforcement through the tribunal route would realistically take {years} years and cost us {amount} in legal and process expenses.",
            "Bucket movement is the concern: {percent}% of accounts in the {dpd} day bucket rolled forward rather than curing this month.",
            "The borrower has offered to bring in a strategic investor, but has produced no term sheet or evidence of funding after {days} days.",
            "Provision held against this account is {percent}% of outstanding, so a settlement at the proposed level would require an additional charge of {amount}.",
        ),
        bullets=(
            "Days past due: {dpd}, classification cut-off {date}",
            "Outstanding: {exposure} plus accrued interest {amount}",
            "Security value: {amount}, valuation {years} years old",
            "Settlement offer: {amount} ({percent}% of book)",
            "Provision coverage: {percent}%",
            "Contact attempts: {count} over {days} days",
            "Estimated enforcement timeline: {years} years",
            "Roll-forward rate from {dpd} day bucket: {percent}%",
        ),
        risks=(
            "Accepting the settlement crystallises the loss now, whereas enforcement keeps the theoretical recovery higher but ties up {amount} of capital for years with an uncertain outcome.",
            "The security valuation is stale, and if the current market value is materially lower then our provision is understated and the settlement looks more attractive than it appears.",
            "Delaying classification past the {date} cut-off is not an option; it would be a reporting breach and the auditors would treat it as evergreening.",
            "The portfolio-level trend matters more than this single case: if the roll-forward rate stays at {percent}% we will see a step change in provisions next quarter.",
        ),
        actions=(
            "Please confirm whether Credit Risk supports the settlement or wants us to proceed with enforcement, by {weekday}.",
            "Can Legal confirm the notice period and the enforceability of the charge before we commit to a route?",
            "I need a fresh valuation of the {region} property before we take this to committee.",
            "Please review the {portfolio} delinquency trend and let me know if you want underwriting criteria tightened.",
        ),
        chat_lines=(
            "{vendor} is at {dpd} dpd, it'll classify as npa at the {quarter} cut-off unless something lands",
            "settlement offer is {amount} against {exposure} outstanding, works out to {percent}% recovery",
            "security valuation on {vendor} is {years} years old, we need a fresh one before committee",
            "{percent}% of the {dpd} bucket rolled forward this month instead of curing, that's the worry",
            "promoters aren't taking calls, {count} attempts in {days} days. moving to enforcement",
            "legal says the tribunal route is realistically {years} years, settlement looks better",
        ),
    ),
    Theme(
        key="fraud",
        subjects=(
            "Card fraud pattern - {region}",
            "Chargeback dispute {ref} - {amount}",
            "Account takeover attempt - {count} customers",
            "Mule account network: {count} accounts",
            "Dispute {ref}: customer claim of unauthorised {rail}",
            "Fraud loss provision - {quarter}",
        ),
        openers=(
            "We are seeing a fraud pattern on card transactions in {region}, with {count} accounts affected over the last {days} days.",
            "Chargeback dispute {ref} for {amount} has been escalated and the customer is now threatening to go to the ombudsman.",
            "There were {count} account takeover attempts on {date}, all following the same social engineering script.",
            "Investigation has identified a network of {count} suspected mule accounts receiving funds from unauthorised {rail} transfers.",
        ),
        background=(
            "The pattern first appeared after the merchant category controls were relaxed to reduce false declines, which improved approval rates but widened the exposure.",
            "This dispute has been open for {days} days and has already been through two rounds of representment with the acquirer.",
            "Customer-reported fraud on the {rail} channel has been rising steadily, and our current authentication step-up only triggers above {amount}.",
            "The mule accounts were all opened within {days} days of each other through the digital channel, using documents that passed automated verification.",
        ),
        details=(
            "Common point of purchase analysis points to a single merchant in {region} where card data was likely compromised, affecting {count} of our cards.",
            "Total attempted fraud value is {amount}, of which we prevented approximately {percent}% through real-time rules.",
            "The customer says they did not authorise the {rail} transfer of {amount}, but the transaction carries a valid authentication token.",
            "Funds were moved onward within {minutes} minutes across {count} accounts, which is consistent with a coordinated mule network rather than isolated incidents.",
            "We have provisionally credited the customer under the dispute rules while the investigation continues, so the {amount} is currently a bank exposure.",
            "All {count} suspect accounts share a device fingerprint and were funded exclusively by inbound transfers with no other activity.",
            "Fraud losses for {quarter} stand at {amount}, which is {percent}% above the provision we set at the start of the year.",
        ),
        bullets=(
            "Accounts affected: {count} across {region}",
            "Attempted value: {amount}, prevented {percent}%",
            "Dispute {ref}: {amount}, open {days} days",
            "Suspected mule accounts: {count}, shared device fingerprint",
            "Onward movement within {minutes} minutes",
            "Provisional credit issued: {amount}",
            "Authentication step-up threshold: {amount}",
            "{quarter} fraud losses: {amount}, {percent}% over provision",
        ),
        risks=(
            "If the customer takes this to the ombudsman and the authentication evidence is judged insufficient, we absorb the {amount} and set an unhelpful precedent for similar claims.",
            "The mule network is the more serious finding, because accounts that passed our onboarding checks means the control failure is at account opening, not at transaction monitoring.",
            "Relaxed merchant category controls improved approval rates, but the fraud cost is now outweighing the revenue benefit and we should revisit that trade-off.",
            "Continued overrun against the {quarter} provision will need to be flagged in the operational risk report.",
        ),
        actions=(
            "Please confirm whether we accept the liability on dispute {ref} or defend it, by {weekday}.",
            "Can you block the identified accounts and confirm the total exposure before end of day?",
            "I need Financial Crime Compliance to review whether the mule network warrants a report to {regulator}.",
            "Please review the step-up threshold and come back with a recommendation before the risk forum on {weekday}.",
        ),
        chat_lines=(
            "seeing a card fraud pattern in {region}, {count} accounts hit in the last {days} days",
            "common point of purchase points to one merchant, we should reissue the affected cards",
            "dispute {ref} for {amount} is going to the ombudsman if we don't resolve it this week",
            "{count} mule accounts all share a device fingerprint, they passed onboarding checks though",
            "funds moved onward in {minutes} minutes, no chance of recall",
            "{quarter} fraud losses are {percent}% over provision, operational risk needs to know",
        ),
    ),
    Theme(
        key="trade_finance",
        subjects=(
            "LC {ref} - discrepant documents",
            "Guarantee {ref} called: {amount}",
            "Trade finance limits - {vendor}",
            "Discrepancy waiver request: {vendor}",
            "Bill discounting - {vendor} {amount}",
            "Correspondent confirmation: {bank}",
        ),
        openers=(
            "Documents presented under letter of credit {ref} for {amount} are discrepant and we need a decision on whether to refuse or seek a waiver.",
            "The performance guarantee {ref} issued for {vendor} has been called for {amount}, and we need to confirm our position within the notice period.",
            "{vendor} has requested an increase in trade finance limits to {amount} to support a new export contract in {region}.",
            "We are being asked to discount bills of {amount} drawn on a buyer in {region}, with confirmation from {bank}.",
        ),
        background=(
            "The underlying commercial contract has been amended twice since the credit was opened, and the documentary requirements were never updated to match.",
            "{vendor} runs a high volume of documentary business through us, and we have historically been flexible on minor discrepancies.",
            "This is the third call on a guarantee in this portfolio this year, which suggests the underlying performance risk was underpriced.",
            "The buyer's country has recently tightened foreign exchange controls, which affects both settlement timing and our willingness to take unconfirmed risk.",
        ),
        details=(
            "The discrepancies are a late presentation of {days} days and an inconsistency between the bill of lading and the packing list description.",
            "Under the credit terms we have {days} banking days to refuse, and that window closes on {date}.",
            "The applicant has indicated they will accept the documents commercially, but we need that waiver in writing before we release.",
            "If we honour the guarantee call, we have recourse to the counter-indemnity from {vendor}, whose account currently shows limited liquidity.",
            "Requested limits of {amount} would take total group exposure to {exposure}, which needs credit committee approval rather than a delegated sanction.",
            "{bank} has confirmed the credit, which reduces our exposure to a bank risk on a {rating} counterparty instead of the underlying buyer risk.",
            "Margin on the discounting is {bps} bps, which is thin given the country risk and the tenor of {days} days.",
        ),
        bullets=(
            "Credit reference {ref}, value {amount}",
            "Discrepancies: late presentation by {days} days, document inconsistency",
            "Refusal deadline: {date}",
            "Guarantee called: {amount}, counter-indemnity from {vendor}",
            "Requested limit: {amount}, group exposure {exposure}",
            "Confirming bank: {bank}, rated {rating}",
            "Tenor: {days} days at {bps} bps margin",
            "Country risk: {region} exchange controls tightened",
        ),
        risks=(
            "If we release documents without a written waiver and the applicant later refuses to pay, the bank carries the loss with a weak legal position.",
            "Honouring the guarantee is not optional once the call is valid; the real question is whether we can actually recover under the counter-indemnity given the borrower's liquidity.",
            "Taking unconfirmed exposure to a buyer in {region} under current exchange controls means settlement risk we are not being paid enough to accept.",
            "Missing the {date} refusal deadline removes our ability to reject the documents altogether, so the timeline is the binding constraint.",
        ),
        actions=(
            "Please confirm by {weekday} whether we refuse the documents or proceed on the applicant's waiver.",
            "Can you obtain the waiver in writing from the applicant before the {date} deadline?",
            "I need Credit Risk to confirm the limit increase before we commit to the customer.",
            "Please check whether {bank} confirmation covers the full tenor before we discount.",
        ),
        chat_lines=(
            "documents under lc {ref} are discrepant, late presentation by {days} days. refusal deadline is {date}",
            "applicant says they'll waive but I need it in writing before we release anything",
            "guarantee {ref} has been called for {amount}, counter indemnity is from {vendor} and their account is tight",
            "{vendor} wants trade limits up to {amount}, that takes group exposure to {exposure}",
            "{bank} confirmed the credit so we're on bank risk not buyer risk, much better",
            "{bps} bps for {days} days tenor on {region} country risk is too thin",
        ),
    ),
    Theme(
        key="wealth",
        subjects=(
            "Suitability review - {count} client files",
            "Portfolio mandate breach: {ref}",
            "Client complaint {ref} - mis-selling allegation",
            "{quarter} performance review - {portfolio}",
            "Concentration limits in discretionary mandates",
            "Product governance: structured note approval",
        ),
        openers=(
            "The suitability review covering {count} client files is complete and {count} of them have documentation gaps that need remediation.",
            "Discretionary mandate {ref} has breached its equity allocation limit, holding {percent}% against a stated maximum.",
            "We have received a complaint under reference {ref} alleging that a structured product was mis-sold to a conservative-profile client.",
            "Attaching the {quarter} performance review for the {portfolio} mandates ahead of the investment committee.",
        ),
        background=(
            "Suitability documentation has been a known weak point since the advisory process moved to the new platform, where risk-profile answers are not always carried over.",
            "This client was onboarded with a conservative risk profile {years} years ago, and the profile has not been refreshed despite a material change in circumstances.",
            "The mandate has been running close to its equity ceiling for two quarters as advisers leaned into the rally rather than rebalancing.",
            "Structured products carry the highest complaint rate in the book, and the product governance committee asked for tighter target-market controls last cycle.",
        ),
        details=(
            "Of the {count} files reviewed, {count} were missing an up-to-date risk profile and {count} had no record of the rationale for the recommendation.",
            "The breach arose from market movement rather than an active trade, but our policy still requires rebalancing within {days} days and that did not happen.",
            "The client's recorded objective was capital preservation, and the product carried capital-at-risk features that do not obviously match that objective.",
            "Performance across the {portfolio} mandates was {percent}% for {quarter}, which is behind the benchmark by {bps} bps after fees.",
            "Concentration in a single issuer reached {percent}% of one portfolio, which exceeds our internal guidance even though it is within the mandate wording.",
            "The adviser's file note is brief and does not evidence that the downside scenarios were explained to the client.",
            "Redress, if we uphold the complaint, would be around {amount} calculated on a restitution basis.",
        ),
        bullets=(
            "Files reviewed: {count}, gaps identified: {count}",
            "Mandate {ref}: equity at {percent}%, limit breached",
            "Rebalancing window: {days} days, exceeded",
            "Client profile: conservative, last refreshed {years} years ago",
            "{quarter} performance: {percent}%, {bps} bps behind benchmark",
            "Single issuer concentration: {percent}%",
            "Estimated redress exposure: {amount}",
            "Complaint reference {ref}, open {days} days",
        ),
        risks=(
            "If the documentation cannot evidence suitability, we are unlikely to successfully defend the complaint and should assume redress of roughly {amount}.",
            "The systemic risk is worse than the individual case: if {percent}% of files have the same gap, the potential redress across the book is a multiple of this complaint.",
            "A passive breach caused by market movement is defensible; failing to rebalance within the policy window is not, and that is what the file shows.",
            "The conduct regulator has been explicit about target-market controls on complex products, so a mis-selling finding here would likely trigger a wider review.",
        ),
        actions=(
            "Please confirm whether we uphold complaint {ref} and, if so, approve the redress calculation.",
            "Can you rebalance mandate {ref} back inside the limit and confirm by {weekday}?",
            "I need advisers to close the documentation gaps on the {count} flagged files within {days} days.",
            "Please review the target-market definition before the product governance committee on {weekday}.",
        ),
        chat_lines=(
            "suitability review found gaps in {count} files, mostly missing risk profiles",
            "mandate {ref} is at {percent}% equity, over the limit. market movement but we should have rebalanced",
            "complaint {ref} alleges mis-selling on a structured note to a conservative client, file note is thin",
            "redress on that complaint would be about {amount} if we uphold it",
            "{portfolio} mandates are {bps} bps behind benchmark after fees for {quarter}",
            "single issuer concentration hit {percent}%, within mandate but over internal guidance",
        ),
    ),
    Theme(
        key="audit",
        subjects=(
            "Internal audit report - {department}",
            "Control failure: {portfolio} underwriting",
            "Audit findings {quarter} - {region} branches",
            "Access recertification gaps - {department}",
            "Management response required: {count} findings",
            "Follow-up audit: overdue remediation",
        ),
        openers=(
            "Internal Audit has issued a draft report on {department} controls for {quarter}, with {count} findings including one rated high.",
            "We have identified a control failure in {portfolio} underwriting where {count} facilities were sanctioned outside delegated authority.",
            "The branch audit for {region} identified {count} findings, mostly around documentation and dual-control adherence.",
            "Follow-up testing shows {count} remediation items are now overdue, some by more than {days} days.",
        ),
        background=(
            "This is the first full review of {department} since the process changes, so some findings reflect transition issues rather than persistent control failures.",
            "The delegated authority matrix was updated {years} years ago but the system limits were never aligned to it, which is how the overrides went unnoticed.",
            "Branch controls in {region} have been tested twice before, and documentation quality was raised on both occasions.",
            "The remediation tracker has grown to {count} open items across the bank, and the oldest date back more than {years} years.",
        ),
        details=(
            "The high-rated finding concerns {count} facilities totalling {amount} that were approved by an officer without the required authority level.",
            "Dual control was not evidenced on {percent}% of the sampled cash transactions, although no losses were identified.",
            "Privileged access was not revoked for {count} users who changed roles, including two who retained approval rights in the core banking system.",
            "Maker-checker was bypassed on {count} limit amendments because the system permits the same user to perform both steps outside business hours.",
            "Testing found that {percent}% of the sampled loan files were missing at least one mandatory document at the time of disbursement.",
            "Management previously committed to remediate this by {date}, and the evidence provided does not demonstrate that the control now operates effectively.",
            "The exception reporting exists but nobody is formally accountable for reviewing it, so exceptions accumulated for {days} days without action.",
        ),
        bullets=(
            "Findings: {count} total, {count} rated high",
            "Facilities outside delegated authority: {count}, {amount}",
            "Dual control not evidenced: {percent}% of sample",
            "Privileged access not revoked: {count} users",
            "Loan files with missing documents: {percent}%",
            "Overdue remediation items: {count}, oldest {days} days",
            "Previous commitment date: {date}, not met",
            "Management response due: {date}",
        ),
        risks=(
            "The authority breach is the finding I would escalate: it is a hard control failure, it is quantifiable at {amount}, and it would be very difficult to explain to {regulator}.",
            "Repeat findings materially change the tone of the audit conclusion, and the audit committee will want to know why the earlier remediation was signed off as complete.",
            "Unrevoked privileged access is the classic precondition for internal fraud, so even without a loss this needs closing quickly.",
            "If exception reporting has no accountable owner, the control effectively does not exist regardless of what the process documentation says.",
        ),
        actions=(
            "Please provide a remediation owner and a realistic target date for each finding by {weekday}.",
            "Can {department} confirm whether the control description in the report is accurate before we finalise?",
            "I need your management response before the audit committee pack goes out on {weekday}.",
            "Please review the draft response and flag anything you disagree with rather than after issue.",
        ),
        chat_lines=(
            "audit came back with {count} findings for {department}, one is rated high",
            "{count} facilities worth {amount} were approved outside delegated authority, that's the high finding",
            "privileged access wasn't revoked for {count} role changers, two still had approval rights",
            "reminder: access recert for {region} closes {weekday}",
            "we have {count} overdue remediation items, oldest is {days} days. audit committee will ask",
            "management response is due {date}, I need owners and dates from each team",
        ),
    ),
)

GREETINGS: tuple[str, ...] = (
    "Hi {first},",
    "Hello {first},",
    "{first},",
    "Hi all,",
    "Morning {first},",
    "Thanks {first},",
)

SIGN_OFFS: tuple[str, ...] = (
    "Best regards,",
    "Thanks,",
    "Regards,",
    "Many thanks,",
    "Kind regards,",
)

REPLY_OPENERS: tuple[str, ...] = (
    "Thanks for pulling this together.",
    "Noted, and largely agreed, though I would frame one point differently.",
    "One clarification on the numbers below before this goes any further.",
    "Adding {other} who owns this area and will need to sign off.",
    "Apologies for the slow reply - I was in the {region} review sessions all week.",
    "This looks right to me, with one caveat that I have set out below.",
    "I have looped in {department} for a second opinion on the risk treatment.",
    "Picking this up from {other} while they are out.",
    "Coming back on this after checking the position with {department}.",
)

BULLET_LEADS: tuple[str, ...] = (
    "Key figures for reference:",
    "Summary of the position:",
    "For the file, the salient points are:",
    "The numbers behind this:",
    "Headline data:",
    "To recap the position as it stands:",
)

CLOSING_NOTES: tuple[str, ...] = (
    "One further point worth noting:",
    "Separately, and for completeness:",
    "For the avoidance of doubt:",
    "It is also worth flagging that",
    "On a related note,",
)

CONFIDENTIALITY_NOTES: tuple[str, ...] = (
    "Please treat this as confidential and restrict circulation to {department} and the named recipients.",
    "This assessment is prepared for internal use only and should not be shared with the customer.",
    "Please do not forward outside the bank; the underlying data is customer confidential.",
    "Circulating on a need-to-know basis given the regulatory sensitivity.",
    "Happy to discuss on a call if any of the above needs more context.",
    "Let me know if you would prefer to walk through this on {weekday} rather than over email.",
)

CHAT_REPLIES: tuple[str, ...] = (
    "ok looking at it now, give me a few minutes",
    "makes sense, thanks for checking",
    "can you share the file reference? I can't find it in the folder",
    "on it, will confirm within the hour",
    "not sure honestly, let me check with {department} and revert",
    "agreed, let's go with that approach",
    "who owns this one? {other} maybe, or is it credit risk",
    "I'll raise it at the morning huddle",
)

CHAT_FOLLOW_UPS: tuple[str, ...] = (
    "need this before the {weekday} committee though, so fairly urgent",
    "{department} will want the numbers first, they always do",
    "have we told the relationship manager yet? they'll get a call from the customer",
    "the deadline is {date} so there isn't much room to move",
    "keeping it off email for now until legal comes back",
    "can you put it in writing so we have something for the file?",
    "same thing happened last {quarter}, we never fixed the root cause",
    "escalating to {department} if we don't have an answer by end of day",
    "audit will absolutely pick this up if we leave it",
    "I'll add it to the risk register either way",
)

QUARTERS: tuple[str, ...] = ("Q1", "Q2", "Q3", "Q4", "H1", "H2", "FY26")
WEEKDAYS: tuple[str, ...] = (
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "end of week",
)

# Attachment catalogue: extension -> (MIME content type, builder key).
ATTACHMENT_TYPES: tuple[tuple[str, str], ...] = (
    ("pdf", "application/pdf"),
    ("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ("csv", "text/csv"),
    ("txt", "text/plain"),
    ("png", "image/png"),
    ("zip", "application/zip"),
)

# Relative weights matching how often each type shows up in a real corpus.
ATTACHMENT_TYPE_WEIGHTS: tuple[int, ...] = (30, 20, 15, 10, 10, 10, 5)

ATTACHMENT_SIZES: tuple[int, ...] = (5 * 1024, 25 * 1024, 100 * 1024, 500 * 1024, 1024 * 1024)
ATTACHMENT_SIZE_WEIGHTS: tuple[int, ...] = (35, 30, 20, 10, 5)

ATTACHMENT_NAME_PARTS: tuple[str, ...] = (
    "credit-memo", "sanction-letter", "kyc-review", "aml-alert-extract",
    "sar-draft", "liquidity-position", "alco-pack", "basel-return",
    "capital-adequacy", "settlement-break", "nostro-reconciliation",
    "npa-schedule", "recovery-plan", "valuation-report", "lc-documents",
    "guarantee-copy", "chargeback-evidence", "fraud-analysis",
    "suitability-review", "portfolio-statement", "audit-findings",
    "remediation-tracker", "exposure-summary", "covenant-compliance",
    "stock-audit", "account-statement",
)

# Document names that belong with each conversation theme, so a credit thread
# does not arrive carrying chargeback evidence.
THEME_ATTACHMENT_NAMES: dict[str, tuple[str, ...]] = {
    "credit": (
        "credit-memo", "sanction-letter", "covenant-compliance",
        "stock-audit", "valuation-report", "exposure-summary",
    ),
    "aml": (
        "kyc-review", "aml-alert-extract", "sar-draft", "account-statement",
        "screening-hit-report",
    ),
    "treasury": (
        "liquidity-position", "alco-pack", "funding-plan",
        "rate-sensitivity-analysis", "deposit-concentration",
    ),
    "regulatory": (
        "basel-return", "capital-adequacy", "regulatory-submission",
        "data-quality-log", "inspection-response",
    ),
    "payments": (
        "settlement-break", "nostro-reconciliation", "incident-timeline",
        "payment-investigation", "unmatched-items",
    ),
    "collections": (
        "npa-schedule", "recovery-plan", "valuation-report",
        "settlement-proposal", "delinquency-report",
    ),
    "fraud": (
        "fraud-analysis", "chargeback-evidence", "dispute-file",
        "mule-account-network", "transaction-extract",
    ),
    "trade_finance": (
        "lc-documents", "guarantee-copy", "discrepancy-notice",
        "bill-of-lading-copy", "trade-limits-summary",
    ),
    "wealth": (
        "suitability-review", "portfolio-statement", "mandate-breach-report",
        "performance-review", "complaint-file",
    ),
    "audit": (
        "audit-findings", "remediation-tracker", "control-testing-results",
        "management-response", "access-review",
    ),
}

DEFAULT_MAX_ATTACHMENT_BYTES = 1_048_576


# --------------------------------------------------------------------------
# Data model
# --------------------------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class Custodian:
    """A fictional employee who sends and receives communications."""

    custodianId: str
    fullName: str
    email: str
    department: str
    jobTitle: str

    @property
    def first_name(self) -> str:
        """Return the custodian's given name."""
        return self.fullName.split(" ", 1)[0]

    def to_dict(self) -> dict[str, str]:
        """Return the custodian as a plain JSON-serialisable dict."""
        return dataclasses.asdict(self)


@dataclasses.dataclass(frozen=True)
class AttachmentPlan:
    """Description of an attachment to be materialised on demand."""

    filename: str
    contentType: str
    extension: str
    targetBytes: int
    seed: int


@dataclasses.dataclass(frozen=True)
class MessagePlan:
    """A fully planned message, before attachment bytes are materialised."""

    externalMessageId: str
    communicationType: str
    sender: str
    recipients: tuple[str, ...]
    subject: str
    body: str
    messageTimestamp: str
    threadId: str
    attachments: tuple[AttachmentPlan, ...]


@dataclasses.dataclass
class SubmissionResult:
    """Outcome of submitting a single message to the ingestion API."""

    externalMessageId: str
    httpStatus: int | None
    requestId: str | None
    deduplicationKey: str | None
    error: str | None
    submittedAt: str | None
    duplicate: bool = False

    @property
    def ok(self) -> bool:
        """Return True when the API accepted the message (or in dry-run mode)."""
        return self.error is None and (self.httpStatus is None or self.httpStatus == HTTP_ACCEPTED)

    def to_dict(self) -> dict[str, Any]:
        """Return the ``_submission`` object recorded in ``messages.jsonl``."""
        return {
            "externalMessageId": self.externalMessageId,
            "httpStatus": self.httpStatus,
            "requestId": self.requestId,
            "deduplicationKey": self.deduplicationKey,
            "duplicate": self.duplicate,
            "error": self.error,
            "submittedAt": self.submittedAt,
        }


# --------------------------------------------------------------------------
# Custodian and content generation
# --------------------------------------------------------------------------


def build_custodians(rng: random.Random, count: int) -> list[Custodian]:
    """Build ``count`` unique fictional custodians with deterministic identities."""
    custodians: list[Custodian] = []
    used_emails: set[str] = set()
    index = 0
    while len(custodians) < count:
        first = rng.choice(FIRST_NAMES)
        last = rng.choice(LAST_NAMES)
        local = f"{first}.{last}".lower()
        email = f"{local}@{EMAIL_DOMAIN}"
        suffix = 2
        while email in used_emails:
            email = f"{local}{suffix}@{EMAIL_DOMAIN}"
            suffix += 1
        used_emails.add(email)
        index += 1
        department = DEPARTMENTS[(index - 1) % len(DEPARTMENTS)]
        custodians.append(
            Custodian(
                custodianId=f"cust-{index:04d}",
                fullName=f"{first} {last}",
                email=email,
                department=department,
                jobTitle=rng.choice(JOB_TITLES[department]),
            )
        )
    return custodians


def _fill(template: str, rng: random.Random, context: dict[str, str]) -> str:
    """Fill a vocabulary template with randomised but plausible values."""
    values: dict[str, str] = {
        "project": rng.choice(PROJECT_CODE_NAMES),
        "vendor": rng.choice(VENDORS),
        "bank": rng.choice(COUNTERPARTY_BANKS),
        "region": rng.choice(REGIONS),
        "quarter": rng.choice(QUARTERS),
        "weekday": rng.choice(WEEKDAYS),
        "portfolio": rng.choice(PORTFOLIOS),
        "regulator": rng.choice(REGULATORS),
        "regreturn": rng.choice(RETURNS),
        "rating": rng.choice(RISK_RATINGS),
        "rail": rng.choice(PAYMENT_RAILS),
        "percent": str(rng.randint(2, 35)),
        "amount": f"INR {rng.randint(2, 90)}.{rng.randint(0, 9)} crore"
        if rng.random() < 0.4
        else f"USD {rng.randint(15, 950)}k",
        "exposure": f"INR {rng.randint(10, 240)} crore"
        if rng.random() < 0.4
        else f"USD {rng.randint(2, 85)}.{rng.randint(0, 9)}m",
        "bps": str(rng.choice((85, 120, 145, 175, 210, 240, 285, 320))),
        "ratio": f"{rng.randint(1, 5)}.{rng.randint(0, 9)}",
        "dpd": str(rng.choice((31, 61, 91, 121, 181, 271))),
        "ref": f"{rng.choice(('ALT', 'DSP', 'LC', 'GTE', 'STL', 'MND'))}-"
        f"{rng.randint(2024, 2026)}-{rng.randint(10000, 99999)}",
        "account": f"XXXXXX{rng.randint(1000, 9999)}",
        "days": str(rng.choice((15, 30, 45, 60, 90))),
        "years": str(rng.choice((3, 5, 7, 10))),
        "count": str(rng.randint(2, 18)),
        "sev": str(rng.randint(1, 3)),
        "minutes": str(rng.choice((12, 25, 40, 75, 120))),
        "date": context.get("date", "next Friday"),
        "department": context.get("department", rng.choice(DEPARTMENTS)),
        "first": context.get("first", "there"),
        "other": context.get("other", "the wider team"),
    }
    values.update({k: v for k, v in context.items() if v})
    return template.format(**values)


def case_facts(rng: random.Random) -> dict[str, str]:
    """Pick the entities a single conversation is about.

    These are resolved once per thread and then reused by every paragraph, so
    a message cannot cite one borrower in the subject and a different one in
    the body, or drift between reporting quarters mid-sentence. Numeric
    figures are deliberately left out: different metrics in the same message
    legitimately carry different numbers.

    The two exceptions are ``percent`` and ``bps``, the headline ratio and
    spread. Those are pinned per conversation because a message that quotes
    one capital ratio in a paragraph and a different one in the summary
    bullets contradicts itself.
    """
    return {
        "percent": str(rng.randint(2, 35)),
        "bps": str(rng.choice((85, 120, 145, 175, 210, 240, 285, 320))),
        "project": rng.choice(PROJECT_CODE_NAMES),
        "vendor": rng.choice(VENDORS),
        "bank": rng.choice(COUNTERPARTY_BANKS),
        "region": rng.choice(REGIONS),
        "quarter": rng.choice(QUARTERS),
        "portfolio": rng.choice(PORTFOLIOS),
        "regulator": rng.choice(REGULATORS),
        "regreturn": rng.choice(RETURNS),
        "rating": rng.choice(RISK_RATINGS),
        "rail": rng.choice(PAYMENT_RAILS),
        "ref": f"{rng.choice(('ALT', 'DSP', 'LC', 'GTE', 'STL', 'MND'))}-"
        f"{rng.randint(2024, 2026)}-{rng.randint(10000, 99999)}",
        "account": f"XXXXXX{rng.randint(1000, 9999)}",
        "exposure": f"INR {rng.randint(10, 240)} crore"
        if rng.random() < 0.4
        else f"USD {rng.randint(2, 85)}.{rng.randint(0, 9)}m",
        "dpd": str(rng.choice((31, 61, 91, 121, 181, 271))),
    }


def _capitalise_subject(subject: str) -> str:
    """Upper-case the first letter without touching the rest of the subject."""
    return subject[:1].upper() + subject[1:] if subject else subject


def _sentence(text: str) -> str:
    """Start a paragraph with a capital letter.

    Some templates open with a placeholder whose value is naturally lower
    case, such as "the prudential regulator", which would otherwise begin a
    sentence in lower case.
    """
    return text[:1].upper() + text[1:] if text else text


def _signature(custodian: Custodian) -> str:
    """Return a plausible email signature block for a custodian."""
    # Avoid "Head of Treasury, Treasury" when the title already names the unit.
    role = (
        custodian.jobTitle
        if custodian.department.lower() in custodian.jobTitle.lower()
        else f"{custodian.jobTitle}, {custodian.department}"
    )
    return f"{custodian.fullName}\n{role}\n{COMPANY_NAME} | {custodian.email}"


def _quote_block(previous_body: str, previous_sender: Custodian, previous_ts: dt.datetime) -> str:
    """Return a quoted-reply block referencing the previous message in a thread."""
    quoted_lines = [line for line in previous_body.splitlines() if line.strip()][:4]
    quoted = "\n".join(f"> {line}" for line in quoted_lines)
    stamp = previous_ts.strftime("%d %b %Y at %H:%M")
    return f"On {stamp}, {previous_sender.fullName} <{previous_sender.email}> wrote:\n{quoted}"


def compose_email(
    rng: random.Random,
    theme: Theme,
    sender: Custodian,
    recipients: Sequence[Custodian],
    context: dict[str, str],
    previous: tuple[str, Custodian, dt.datetime] | None,
) -> tuple[str, str]:
    """Compose an email subject and body, threading a reply when ``previous`` is set."""
    primary = recipients[0]
    local_context = dict(context)
    local_context["first"] = primary.first_name
    local_context["department"] = sender.department
    local_context["other"] = rng.choice([r.fullName for r in recipients] + ["the wider team"])

    subject = _capitalise_subject(_fill(rng.choice(theme.subjects), rng, local_context))
    paragraphs: list[str] = [_fill(rng.choice(GREETINGS), rng, local_context)]

    if previous is None:
        paragraphs.append(_sentence(_fill(rng.choice(theme.openers), rng, local_context)))
    else:
        paragraphs.append(_sentence(_fill(rng.choice(REPLY_OPENERS), rng, local_context)))
        # A reply still restates the substance so the body stands alone.
        paragraphs.append(_sentence(_fill(rng.choice(theme.openers), rng, local_context)))

    # Background paragraph giving the relationship or account history.
    paragraphs.append(_sentence(_fill(rng.choice(theme.background), rng, local_context)))

    # Two to four substantive detail paragraphs.
    detail_count = rng.randint(2, min(4, len(theme.details)))
    for detail in rng.sample(theme.details, k=detail_count):
        paragraphs.append(_sentence(_fill(detail, rng, local_context)))

    # A bulleted summary block, which is how bankers actually write.
    bullet_count = rng.randint(3, min(5, len(theme.bullets)))
    bullet_lines = [
        f"  - {_sentence(_fill(bullet, rng, local_context))}"
        for bullet in rng.sample(theme.bullets, k=bullet_count)
    ]
    paragraphs.append(
        f"{rng.choice(BULLET_LEADS)}\n" + "\n".join(bullet_lines)
    )

    # Risk or impact assessment.
    paragraphs.append(_sentence(_fill(rng.choice(theme.risks), rng, local_context)))

    # Occasionally a second theme detail as a closing observation.
    if rng.random() < 0.45:
        paragraphs.append(
            f"{rng.choice(CLOSING_NOTES)} {_fill(rng.choice(theme.details), rng, local_context)}"
        )

    paragraphs.append(_sentence(_fill(rng.choice(theme.actions), rng, local_context)))
    paragraphs.append(_sentence(_fill(rng.choice(CONFIDENTIALITY_NOTES), rng, local_context)))
    paragraphs.append(f"{rng.choice(SIGN_OFFS)}\n{_signature(sender)}")

    if previous is not None:
        prev_body, prev_sender, prev_ts = previous
        paragraphs.append(_quote_block(prev_body, prev_sender, prev_ts))

    return subject, "\n\n".join(paragraphs)


def compose_chat(
    rng: random.Random,
    theme: Theme,
    sender: Custodian,
    recipients: Sequence[Custodian],
    context: dict[str, str],
    is_reply: bool,
) -> tuple[str, str]:
    """Compose a short chat topic and message body."""
    local_context = dict(context)
    local_context["first"] = recipients[0].first_name
    local_context["department"] = sender.department
    local_context["other"] = rng.choice([r.first_name for r in recipients])

    topic_seed = _capitalise_subject(_fill(rng.choice(theme.subjects), rng, local_context))
    subject = topic_seed if len(topic_seed) <= 60 else topic_seed[:57].rstrip() + "..."

    lines: list[str] = []

    if is_reply and rng.random() < 0.6:
        lines.append(_fill(rng.choice(CHAT_REPLIES), rng, local_context))
    else:
        lines.append(_fill(rng.choice(theme.chat_lines), rng, local_context))

    # Chats are short by nature but rarely a single clause: add follow-up
    # lines so the body still carries searchable substance.
    for _ in range(rng.randint(1, 3)):
        if rng.random() < 0.55:
            lines.append(_fill(rng.choice(theme.chat_lines), rng, local_context))
        else:
            lines.append(_fill(rng.choice(CHAT_FOLLOW_UPS), rng, local_context))

    if rng.random() < 0.3:
        lines.append(_fill(rng.choice(theme.bullets), rng, local_context).lower())

    return subject, "\n".join(lines)


# --------------------------------------------------------------------------
# Timestamps
# --------------------------------------------------------------------------

# Hour-of-day weights: business hours dominate, with a light after-hours tail.
_HOUR_WEIGHTS: tuple[int, ...] = (
    1, 1, 1, 1, 1, 2, 4, 10, 30, 70, 90, 85,   # 00:00 - 11:00
    60, 70, 88, 92, 80, 55, 30, 18, 12, 8, 4, 2,  # 12:00 - 23:00
)


def random_business_timestamp(rng: random.Random, window_start: dt.datetime, window_days: int) -> dt.datetime:
    """Return a random UTC timestamp inside the window, weighted to business hours."""
    day_offset = rng.randrange(max(window_days, 1))
    candidate = window_start + dt.timedelta(days=day_offset)
    # Weekends are far quieter than weekdays.
    if candidate.weekday() >= 5 and rng.random() < 0.85:
        candidate -= dt.timedelta(days=rng.randint(1, 2))
    hour = rng.choices(range(24), weights=_HOUR_WEIGHTS, k=1)[0]
    return candidate.replace(
        hour=hour,
        minute=rng.randrange(60),
        second=rng.randrange(60),
        microsecond=0,
    )


def iso_utc(value: dt.datetime) -> str:
    """Format a datetime as ISO-8601 UTC with a trailing ``Z``."""
    return value.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# --------------------------------------------------------------------------
# Attachment binaries
# --------------------------------------------------------------------------


def _filler_words(rng: random.Random, count: int) -> str:
    """Return ``count`` plausible banking words for padding text payloads."""
    words = (
        "exposure", "facility", "covenant", "collateral", "provision",
        "liquidity", "settlement", "reconciliation", "remittance", "mandate",
        "underwriting", "delinquency", "recovery", "sanction", "screening",
        "counterparty", "nostro", "tranche", "drawdown", "accrual",
        "impairment", "capital", "tier", "buffer", "disbursement", "guarantee",
        "documentary", "chargeback", "suitability", "custodian", "audit",
        "control", "threshold", "variance", "retention", "disclosure",
    )
    return " ".join(rng.choice(words) for _ in range(max(count, 0)))


def _filler_bytes(rng: random.Random, length: int) -> bytes:
    """Return exactly ``length`` bytes of printable ASCII filler text."""
    if length <= 0:
        return b""
    text = _filler_words(rng, length // 6 + 2).encode("ascii")
    while len(text) < length:
        text += b" " + _filler_words(rng, length // 6 + 2).encode("ascii")
    return text[:length]


_ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)


def _zip_write(archive: zipfile.ZipFile, name: str, data: bytes | str, stored: bool = False) -> None:
    """Write a ZIP entry with a fixed timestamp so archives are reproducible."""
    info = zipfile.ZipInfo(name, date_time=_ZIP_EPOCH)
    info.external_attr = 0o600 << 16
    payload = data.encode("utf-8") if isinstance(data, str) else data
    archive.writestr(info, payload, compress_type=zipfile.ZIP_STORED if stored else archive.compression)


def build_txt(rng: random.Random, target_bytes: int) -> bytes:
    """Build a plain-text memo padded to roughly ``target_bytes``."""
    header = (
        "EXAMPLE BANK - INTERNAL MEMORANDUM (SYNTHETIC TEST DATA)\n"
        "=======================================================\n"
        "Classification: Internal - Customer Confidential\n"
        "No real customer, account or transaction data is contained herein.\n\n"
    )
    buffer = io.StringIO()
    buffer.write(header)
    line_no = 1
    while buffer.tell() < target_bytes:
        buffer.write(f"{line_no:04d}. {_filler_words(rng, 12)}\n")
        line_no += 1
    return buffer.getvalue().encode("utf-8")[:target_bytes]


def build_csv(rng: random.Random, target_bytes: int) -> bytes:
    """Build a CSV transaction extract padded to roughly ``target_bytes``.

    Columns mirror a core-banking statement extract. Account numbers are
    masked and entirely fictional.
    """
    buffer = io.StringIO()
    buffer.write(
        "txn_id,value_date,account_masked,counterparty,rail,dr_cr,"
        "amount,currency,balance,branch,status\n"
    )
    row = 1
    while buffer.tell() < target_bytes:
        buffer.write(
            f"TXN{row:08d},"
            f"2026-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d},"
            f"XXXXXX{rng.randint(1000, 9999)},"
            f"{rng.choice(VENDORS).replace(',', ' ')},"
            f"{rng.choice(PAYMENT_RAILS).replace(',', ' ')},"
            f"{rng.choice(('DR', 'CR'))},"
            f"{rng.randint(500, 2500000)}.{rng.randint(0, 99):02d},"
            f"{rng.choice(('INR', 'USD', 'EUR', 'GBP'))},"
            f"{rng.randint(1000, 9500000)}.{rng.randint(0, 99):02d},"
            f"BR-{rng.randint(100, 999)},"
            f"{rng.choice(('SETTLED', 'PENDING', 'RETURNED', 'ON HOLD'))}\n"
        )
        row += 1
    return buffer.getvalue().encode("utf-8")[:target_bytes]


def build_pdf(rng: random.Random, target_bytes: int) -> bytes:
    """Build a single-page PDF 1.7 file with a valid xref table and trailer."""
    text_lines = [f"Example Bank - synthetic document {rng.randint(1000, 9999)}"]
    text_lines.extend(_filler_words(rng, 8) for _ in range(6))
    content_ops = ["BT", "/F1 12 Tf", "72 760 Td", "14 TL"]
    content_ops.extend(f"({line}) Tj T*" for line in text_lines)
    content_ops.append("ET")
    stream = "\n".join(content_ops).encode("ascii", "replace")

    objects: list[bytes] = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]

    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    # Pad with PDF comment lines, which are legal anywhere in the body.
    overhead = 260 + sum(len(o) for o in objects)
    padding_needed = max(0, target_bytes - overhead)
    while padding_needed > 3:
        chunk = min(padding_needed, 96)
        out += b"% " + _filler_bytes(rng, chunk - 3) + b"\n"
        padding_needed -= chunk

    offsets: list[int] = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode("ascii") + body + b"\nendobj\n"

    xref_offset = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode("ascii")
    out += b"0000000000 65535 f \n"
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode("ascii")
    out += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref_offset}\n".encode("ascii")
        + b"%%EOF\n"
    )
    return bytes(out)


def _png_chunk(chunk_type: bytes, payload: bytes) -> bytes:
    """Return a length-prefixed, CRC-suffixed PNG chunk."""
    return (
        struct.pack(">I", len(payload))
        + chunk_type
        + payload
        + struct.pack(">I", binascii.crc32(chunk_type + payload) & 0xFFFFFFFF)
    )


def build_png(rng: random.Random, target_bytes: int) -> bytes:
    """Build a valid RGB PNG with noise pixels, padded via a ``tEXt`` chunk."""
    # Size the noise image so the compressed IDAT stays inside the target,
    # then top up to the exact target with a tEXt comment chunk.
    width = height = max(16, min(256, int(((target_bytes * 0.7) / 3.0) ** 0.5)))
    raw = bytearray()
    for _ in range(height):
        raw.append(0)  # filter type 0 (None) per scanline
        raw.extend(rng.randbytes(width * 3))
    idat = zlib.compress(bytes(raw), level=6)

    out = bytearray(b"\x89PNG\r\n\x1a\n")
    out += _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    out += _png_chunk(b"IDAT", idat)

    padding_needed = target_bytes - len(out) - 12 - 24  # tEXt overhead + IEND
    if padding_needed > 0:
        text = b"Comment\x00" + _filler_bytes(rng, padding_needed)
        out += _png_chunk(b"tEXt", text)
    out += _png_chunk(b"IEND", b"")
    return bytes(out)


def build_zip(rng: random.Random, target_bytes: int) -> bytes:
    """Build a real ZIP archive containing a memo, a CSV and stored padding."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        _zip_write(archive, "readme.txt", build_txt(rng, 512))
        _zip_write(archive, "ledger.csv", build_csv(rng, 2048))
        remaining = target_bytes - buffer.tell() - 512
        if remaining > 0:
            _zip_write(archive, "attachments/payload.bin", rng.randbytes(remaining), stored=True)
    return buffer.getvalue()


_CONTENT_TYPES_DOCX = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-'
    'officedocument.wordprocessingml.document.main+xml"/>'
    "</Types>"
)

_CONTENT_TYPES_XLSX = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-'
    'officedocument.spreadsheetml.sheet.main+xml"/>'
    '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-'
    'officedocument.spreadsheetml.worksheet+xml"/>'
    "</Types>"
)


def _rels(target: str, rel_type: str) -> str:
    """Return a minimal package-level ``.rels`` document for an OOXML part."""
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        f'<Relationship Id="rId1" Type="{rel_type}" Target="{target}"/>'
        "</Relationships>"
    )


def build_docx(rng: random.Random, target_bytes: int) -> bytes:
    """Build a minimal but structurally valid DOCX padded with paragraphs."""
    paragraphs = [
        "Example Bank - synthetic discovery document",
        f"Reference {rng.randint(100000, 999999)} (test data only)",
    ]
    body_chars = 0
    # 52 characters of XML markup wrap each paragraph of text.
    while body_chars < max(0, target_bytes - 2048):
        text = _filler_words(rng, 24)
        paragraphs.append(text)
        body_chars += len(text) + 52
    xml_paragraphs = "".join(
        f"<w:p><w:r><w:t xml:space=\"preserve\">{p}</w:t></w:r></w:p>" for p in paragraphs
    )
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        f"<w:body>{xml_paragraphs}</w:body></w:document>"
    )
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_STORED) as archive:
        _zip_write(archive, "[Content_Types].xml", _CONTENT_TYPES_DOCX)
        _zip_write(
            archive,
            "_rels/.rels",
            _rels(
                "word/document.xml",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
            ),
        )
        _zip_write(archive, "word/document.xml", document)
    return buffer.getvalue()


def build_xlsx(rng: random.Random, target_bytes: int) -> bytes:
    """Build a minimal but structurally valid XLSX padded with inline-string rows."""
    rows: list[str] = [
        '<row r="1"><c r="A1" t="inlineStr"><is><t>Cost centre</t></is></c>'
        '<c r="B1" t="inlineStr"><is><t>Project</t></is></c>'
        '<c r="C1" t="inlineStr"><is><t>Amount</t></is></c></row>'
    ]
    approx = 0
    row_no = 2
    while approx < max(0, target_bytes - 3072):
        cell = (
            f'<row r="{row_no}"><c r="A{row_no}" t="inlineStr"><is><t>CC-{rng.randint(1000, 9999)}</t></is></c>'
            f'<c r="B{row_no}" t="inlineStr"><is><t>{rng.choice(PROJECT_CODE_NAMES)}</t></is></c>'
            f'<c r="C{row_no}"><v>{rng.randint(500, 250000)}</v></c></row>'
        )
        rows.append(cell)
        approx += len(cell)
        row_no += 1
    sheet = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f"<sheetData>{''.join(rows)}</sheetData></worksheet>"
    )
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets><sheet name="Ledger" sheetId="1" r:id="rId1"/></sheets></workbook>'
    )
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_STORED) as archive:
        _zip_write(archive, "[Content_Types].xml", _CONTENT_TYPES_XLSX)
        _zip_write(
            archive,
            "_rels/.rels",
            _rels(
                "xl/workbook.xml",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
            ),
        )
        _zip_write(
            archive,
            "xl/_rels/workbook.xml.rels",
            _rels(
                "worksheets/sheet1.xml",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet",
            ),
        )
        _zip_write(archive, "xl/workbook.xml", workbook)
        _zip_write(archive, "xl/worksheets/sheet1.xml", sheet)
    return buffer.getvalue()


ATTACHMENT_BUILDERS: dict[str, Callable[[random.Random, int], bytes]] = {
    "pdf": build_pdf,
    "docx": build_docx,
    "xlsx": build_xlsx,
    "csv": build_csv,
    "txt": build_txt,
    "png": build_png,
    "zip": build_zip,
}


def materialise_attachment(plan: AttachmentPlan, max_bytes: int) -> bytes:
    """Build the attachment bytes for ``plan``, truncating above ``max_bytes``."""
    builder = ATTACHMENT_BUILDERS[plan.extension]
    target = min(plan.targetBytes, max_bytes)
    data = builder(random.Random(plan.seed), target)

    # Container formats overshoot slightly; shrink the target proportionally
    # rather than truncating, which would corrupt the file.
    for _ in range(3):
        if len(data) <= max_bytes:
            return data
        if plan.extension in ("txt", "csv"):
            return data[:max_bytes]
        target = max(1024, int(target * max_bytes / len(data) * 0.97))
        data = builder(random.Random(plan.seed), target)
    return data[:max_bytes] if plan.extension in ("txt", "csv") else data


# --------------------------------------------------------------------------
# Corpus planning
# --------------------------------------------------------------------------


def plan_attachments(rng: random.Random, index: int, subject_hint: str) -> tuple[AttachmentPlan, ...]:
    """Plan one or two attachments for a message that has been chosen to carry them.

    ``subject_hint`` is the theme key, which selects a document name that
    belongs with the conversation: a credit thread carries a credit memo, not
    a chargeback file.
    """
    plans: list[AttachmentPlan] = []
    name_pool = THEME_ATTACHMENT_NAMES.get(subject_hint, ATTACHMENT_NAME_PARTS)
    for position in range(1 if rng.random() < 0.85 else 2):
        extension, content_type = rng.choices(ATTACHMENT_TYPES, weights=ATTACHMENT_TYPE_WEIGHTS, k=1)[0]
        target = rng.choices(ATTACHMENT_SIZES, weights=ATTACHMENT_SIZE_WEIGHTS, k=1)[0]
        stem = rng.choice(name_pool)
        if subject_hint:
            stem = f"{stem}-{index:06d}"
        plans.append(
            AttachmentPlan(
                filename=f"{stem}-{position + 1}.{extension}",
                contentType=content_type,
                extension=extension,
                targetBytes=target,
                seed=rng.randrange(2**31),
            )
        )
    return tuple(plans)


def plan_corpus(
    rng: random.Random,
    custodians: Sequence[Custodian],
    total_messages: int,
    email_ratio: float,
    attachment_rate: float,
    window_days: int,
    external_id_prefix: str = "corpus",
) -> list[MessagePlan]:
    """Plan the full corpus as threaded conversations ordered by generation index.

    ``external_id_prefix`` namespaces the source identifiers. The ingestion
    API treats ``externalMessageId`` as a stable source key, so two corpora
    that reuse the same prefix are deliberately deduplicated against each
    other. Use a distinct prefix to load a genuinely separate corpus.
    """
    # Anchor the window on UTC midnight rather than "right now" so that repeated
    # runs on the same day with the same seed produce byte-identical timestamps.
    window_end = dt.datetime.now(dt.timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    window_start = window_end - dt.timedelta(days=window_days)

    plans: list[MessagePlan] = []
    thread_index = 0
    while len(plans) < total_messages:
        thread_index += 1
        thread_id = f"thread-{thread_index:05d}"
        thread_length = min(rng.randint(1, 6), total_messages - len(plans))
        theme = rng.choice(THEMES)
        is_email = rng.random() < email_ratio
        comm_type = "EMAIL" if is_email else "CHAT"
        # Resolved once so the whole thread stays about the same borrower,
        # return, portfolio and reporting period.
        facts = case_facts(rng)

        participants = rng.sample(list(custodians), k=min(len(custodians), rng.randint(2, 5)))
        base_subject: str | None = None
        timestamp = random_business_timestamp(rng, window_start, window_days)
        previous: tuple[str, Custodian, dt.datetime] | None = None

        for position in range(thread_length):
            index = len(plans) + 1
            sender = participants[position % len(participants)]
            recipients = [p for p in participants if p.email != sender.email]
            if not recipients:
                recipients = [p for p in custodians if p.email != sender.email][:1]

            context = dict(facts)
            context["date"] = timestamp.strftime("%d %b %Y")
            context["department"] = sender.department
            if comm_type == "EMAIL":
                subject, body = compose_email(rng, theme, sender, recipients, context, previous)
                if base_subject is None:
                    base_subject = subject
                elif not base_subject.startswith("Re: "):
                    subject = f"Re: {base_subject}"
                else:
                    subject = base_subject
            else:
                subject, body = compose_chat(rng, theme, sender, recipients, context, position > 0)
                if base_subject is None:
                    base_subject = subject
                else:
                    subject = base_subject

            attachments: tuple[AttachmentPlan, ...] = ()
            if rng.random() < attachment_rate:
                attachments = plan_attachments(rng, index, theme.key)

            plans.append(
                MessagePlan(
                    externalMessageId=f"{external_id_prefix}-{index:06d}",
                    communicationType=comm_type,
                    sender=sender.email,
                    recipients=tuple(r.email for r in recipients),
                    subject=subject,
                    body=body,
                    messageTimestamp=iso_utc(timestamp),
                    threadId=thread_id,
                    attachments=attachments,
                )
            )

            previous = (body, sender, timestamp)
            gap_minutes = rng.randint(3, 90) if comm_type == "CHAT" else rng.randint(20, 2880)
            timestamp = min(timestamp + dt.timedelta(minutes=gap_minutes), window_end)

    return plans


def build_payload(
    plan: MessagePlan,
    attachments_dir: Path,
    max_attachment_bytes: int,
) -> tuple[dict[str, Any], list[dict[str, Any]], int]:
    """Materialise attachments to disk and build the API request payload.

    Returns the request payload, a disk-friendly attachment manifest (without
    base64 content) and the total number of attachment bytes written.
    """
    api_attachments: list[dict[str, str]] = []
    manifest: list[dict[str, Any]] = []
    total_bytes = 0

    for position, attachment in enumerate(plan.attachments, start=1):
        data = materialise_attachment(attachment, max_attachment_bytes)
        if len(data) > max_attachment_bytes:
            continue  # Defensive: never send oversized attachments.
        local_name = f"{plan.externalMessageId}-{position}-{attachment.filename}"
        local_path = attachments_dir / local_name
        local_path.write_bytes(data)
        total_bytes += len(data)
        api_attachments.append(
            {
                "filename": attachment.filename,
                "contentType": attachment.contentType,
                "contentBase64": base64.b64encode(data).decode("ascii"),
            }
        )
        manifest.append(
            {
                "filename": attachment.filename,
                "contentType": attachment.contentType,
                "sizeBytes": len(data),
                "localPath": f"attachments/{local_name}",
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        )

    payload: dict[str, Any] = {
        "communicationType": plan.communicationType,
        "sender": plan.sender,
        "recipients": list(plan.recipients),
        "subject": plan.subject,
        "body": plan.body,
        "messageTimestamp": plan.messageTimestamp,
        "threadId": plan.threadId,
        "externalMessageId": plan.externalMessageId,
    }
    if api_attachments:
        payload["attachments"] = api_attachments
    return payload, manifest, total_bytes


# --------------------------------------------------------------------------
# Submission
# --------------------------------------------------------------------------


class RateLimiter:
    """Thread-safe token-free rate limiter enforcing a global requests/second cap."""

    def __init__(self, rate_per_second: float) -> None:
        self._interval = 1.0 / rate_per_second if rate_per_second > 0 else 0.0
        self._lock = threading.Lock()
        self._next_slot = time.monotonic()

    def acquire(self) -> None:
        """Block until the caller is allowed to issue its next request."""
        if self._interval <= 0:
            return
        with self._lock:
            now = time.monotonic()
            slot = max(now, self._next_slot)
            self._next_slot = slot + self._interval
        delay = slot - time.monotonic()
        if delay > 0:
            time.sleep(delay)


def submit_message(
    session: requests.Session,
    url: str,
    payload: dict[str, Any],
    timeout: float,
    attempts: int = 3,
) -> SubmissionResult:
    """POST one message, retrying 5xx/timeout/connection failures with backoff."""
    external_id = str(payload.get("externalMessageId"))
    last_error = "unknown error"
    status: int | None = None

    for attempt in range(1, attempts + 1):
        try:
            response = session.post(url, json=payload, timeout=timeout)
            status = response.status_code
            if status == HTTP_ACCEPTED:
                try:
                    data = response.json()
                except ValueError:
                    data = {}
                return SubmissionResult(
                    externalMessageId=external_id,
                    httpStatus=status,
                    requestId=data.get("requestId"),
                    deduplicationKey=data.get("deduplicationKey"),
                    duplicate=bool(data.get("duplicate")),
                    error=None,
                    submittedAt=iso_utc(dt.datetime.now(dt.timezone.utc)),
                )
            if 400 <= status < 500:
                # Validation problems are permanent; never retry them.
                return SubmissionResult(
                    externalMessageId=external_id,
                    httpStatus=status,
                    requestId=None,
                    deduplicationKey=None,
                    error=_describe_client_error(response),
                    submittedAt=iso_utc(dt.datetime.now(dt.timezone.utc)),
                )
            last_error = f"HTTP {status}: {response.text[:200]}"
        except (requests.Timeout, requests.ConnectionError) as exc:
            last_error = f"{type(exc).__name__}: {exc}"
        except requests.RequestException as exc:  # pragma: no cover - defensive
            last_error = f"{type(exc).__name__}: {exc}"

        if attempt < attempts:
            time.sleep(min(2.0 ** (attempt - 1), 8.0))

    return SubmissionResult(
        externalMessageId=external_id,
        httpStatus=status,
        requestId=None,
        deduplicationKey=None,
        error=last_error,
        submittedAt=iso_utc(dt.datetime.now(dt.timezone.utc)),
    )


def _describe_client_error(response: requests.Response) -> str:
    """Summarise a 4xx error body, including API field errors when present."""
    try:
        data = response.json()
    except ValueError:
        return f"HTTP {response.status_code}: {response.text[:200]}"
    field_errors = data.get("fieldErrors") or []
    if field_errors:
        details = "; ".join(
            f"{item.get('field')}: {item.get('message')}" for item in field_errors if isinstance(item, dict)
        )
        return f"HTTP {response.status_code}: {data.get('message', 'validation failed')} ({details})"
    return f"HTTP {response.status_code}: {data.get('message', response.text[:200])}"


# --------------------------------------------------------------------------
# Optional S3/MinIO archival
# --------------------------------------------------------------------------


def upload_attachments_to_s3(attachments_dir: Path, prefix: str) -> tuple[int, str | None]:
    """Upload local attachment files to S3/MinIO, returning (uploaded, error)."""
    try:
        import boto3  # noqa: PLC0415 - optional dependency, imported lazily
    except ImportError:
        return 0, "boto3 is not installed; run 'pip install boto3' to use --upload-to-s3"

    bucket = os.environ.get("S3_BUCKET")
    if not bucket:
        return 0, "S3_BUCKET is not set; skipping S3 upload"

    client_kwargs: dict[str, Any] = {}
    if os.environ.get("S3_ENDPOINT"):
        client_kwargs["endpoint_url"] = os.environ["S3_ENDPOINT"]
    if os.environ.get("AWS_REGION"):
        client_kwargs["region_name"] = os.environ["AWS_REGION"]

    try:
        client = boto3.client("s3", **client_kwargs)
    except Exception as exc:  # noqa: BLE001 - boto3 raises many config errors
        return 0, f"could not create S3 client: {exc}"

    uploaded = 0
    for path in sorted(attachments_dir.glob("*")):
        if not path.is_file():
            continue
        try:
            client.upload_file(str(path), bucket, f"{prefix}/{path.name}")
            uploaded += 1
        except Exception as exc:  # noqa: BLE001 - keep archival best-effort
            return uploaded, f"upload failed for {path.name}: {exc}"
    return uploaded, None


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def _env_int(name: str, default: int) -> int:
    """Read an integer default from the environment, falling back on bad input."""
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    """Read a float default from the environment, falling back on bad input."""
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    """Parse CLI arguments; environment variables supply defaults, flags win."""
    parser = argparse.ArgumentParser(
        prog="generate_corpus.py",
        description=(
            "Generate a synthetic corpus of fictional business communications and "
            "submit it to the Discovery Hub ingestion API."
        ),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--messages", type=int, default=_env_int("CORPUS_MESSAGES", 10000),
                        help="Number of messages to generate.")
    parser.add_argument("--custodians", type=int, default=_env_int("CORPUS_CUSTODIANS", 25),
                        help="Number of fictional custodians.")
    parser.add_argument("--rate", type=float, default=_env_float("CORPUS_RATE", 20.0),
                        help="Maximum total submissions per second (client-side throttle).")
    parser.add_argument("--output", default=os.environ.get("CORPUS_OUTPUT", "generated-corpus"),
                        help="Output directory for generated artefacts.")
    parser.add_argument("--api-url", default=os.environ.get("API_URL", "http://localhost:8081"),
                        help="Base URL of the ingestion service.")
    parser.add_argument("--attachment-rate", type=float, default=_env_float("CORPUS_ATTACHMENT_RATE", 0.08),
                        help="Fraction of messages that carry attachments (0.0-1.0).")
    parser.add_argument("--email-ratio", type=float, default=_env_float("CORPUS_EMAIL_RATIO", 0.65),
                        help="Fraction of threads that are EMAIL rather than CHAT.")
    parser.add_argument("--seed", type=int, default=_env_int("CORPUS_SEED", 42),
                        help="Random seed; identical seeds produce identical corpora.")
    parser.add_argument("--external-id-prefix",
                        default=os.environ.get("CORPUS_ID_PREFIX", "corpus"),
                        help="Prefix for externalMessageId values. The ingestion API "
                             "deduplicates on this identifier, so reuse the same prefix "
                             "to re-submit a corpus idempotently, or change it to load a "
                             "separate corpus alongside an existing one.")
    parser.add_argument("--window-days", type=int, default=_env_int("CORPUS_WINDOW_DAYS", 365),
                        help="Size of the timestamp window ending now, in days.")
    parser.add_argument("--dry-run", action="store_true",
                        help="Generate files locally without calling the ingestion API.")
    parser.add_argument("--resume", action="store_true",
                        help="Skip messages already recorded as submitted in messages.jsonl.")
    parser.add_argument("--workers", type=int, default=_env_int("CORPUS_WORKERS", 4),
                        help="Concurrent submitter threads (total rate still capped by --rate).")
    parser.add_argument("--timeout", type=float, default=_env_float("CORPUS_TIMEOUT", 30.0),
                        help="HTTP timeout per request, in seconds.")
    parser.add_argument("--max-attachment-bytes", type=int,
                        default=_env_int("CORPUS_MAX_ATTACHMENT_BYTES", DEFAULT_MAX_ATTACHMENT_BYTES),
                        help="Hard cap on a single attachment's size in bytes.")
    parser.add_argument("--include-base64-in-jsonl", action="store_true",
                        help="Record attachment base64 in messages.jsonl (large files; off by default).")
    parser.add_argument("--upload-to-s3", action="store_true",
                        help="Also archive local attachment files to S3/MinIO (needs boto3 and S3_* env vars).")
    parser.add_argument("--s3-prefix", default=os.environ.get("CORPUS_S3_PREFIX", "corpus-attachments"),
                        help="Key prefix used when archiving attachments to S3.")

    args = parser.parse_args(argv)
    _validate_args(parser, args)
    return args


def _validate_args(parser: argparse.ArgumentParser, args: argparse.Namespace) -> None:
    """Reject argument combinations that cannot produce a usable corpus."""
    if args.messages < 1:
        parser.error("--messages must be at least 1")
    if args.custodians < 2:
        parser.error("--custodians must be at least 2")
    if args.rate <= 0:
        parser.error("--rate must be greater than 0")
    if not 0.0 <= args.attachment_rate <= 1.0:
        parser.error("--attachment-rate must be between 0.0 and 1.0")
    if not 0.0 <= args.email_ratio <= 1.0:
        parser.error("--email-ratio must be between 0.0 and 1.0")
    if args.workers < 1:
        parser.error("--workers must be at least 1")
    if args.window_days < 1:
        parser.error("--window-days must be at least 1")
    if args.max_attachment_bytes < 1024:
        parser.error("--max-attachment-bytes must be at least 1024")


# --------------------------------------------------------------------------
# Run orchestration
# --------------------------------------------------------------------------


def load_completed_ids(jsonl_path: Path, dry_run: bool) -> set[str]:
    """Return externalMessageIds already recorded as successfully processed."""
    completed: set[str] = set()
    if not jsonl_path.exists():
        return completed
    with jsonl_path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            submission = record.get("_submission") or {}
            external_id = submission.get("externalMessageId") or record.get("externalMessageId")
            if not external_id:
                continue
            status = submission.get("httpStatus")
            if status == HTTP_ACCEPTED or (dry_run and submission.get("error") is None):
                completed.add(str(external_id))
    return completed


class ProgressReporter:
    """Thread-safe counters plus periodic progress lines on stdout."""

    def __init__(self, total: int, every: int = 100) -> None:
        self.total = total
        self.every = every
        self.processed = 0
        self.submitted = 0
        self.failed = 0
        self.duplicates = 0
        self.attachments = 0
        self.attachment_bytes = 0
        self._lock = threading.Lock()
        self._started = time.monotonic()

    def record(
        self,
        ok: bool,
        attachment_count: int,
        attachment_bytes: int,
        duplicate: bool = False,
    ) -> None:
        """Record one processed message and print progress at the configured interval."""
        with self._lock:
            self.processed += 1
            if ok:
                self.submitted += 1
                if duplicate:
                    self.duplicates += 1
            else:
                self.failed += 1
            self.attachments += attachment_count
            self.attachment_bytes += attachment_bytes
            processed = self.processed
            submitted = self.submitted
            failed = self.failed
            duplicates = self.duplicates
        if processed % self.every == 0 or processed == self.total:
            elapsed = max(time.monotonic() - self._started, 1e-6)
            print(
                f"[{processed}/{self.total}] submitted={submitted} failed={failed} "
                f"duplicates={duplicates} "
                f"rate={processed / elapsed:.1f}/s elapsed={elapsed:.1f}s",
                flush=True,
            )

    @property
    def elapsed(self) -> float:
        """Seconds since the reporter was created."""
        return time.monotonic() - self._started


def run(args: argparse.Namespace) -> int:
    """Generate the corpus, submit it and write the output artefacts."""
    output_dir = Path(args.output).expanduser().resolve()
    attachments_dir = output_dir / "attachments"
    attachments_dir.mkdir(parents=True, exist_ok=True)
    jsonl_path = output_dir / "messages.jsonl"

    rng = random.Random(args.seed)
    custodians = build_custodians(rng, args.custodians)
    (output_dir / "custodians.json").write_text(
        json.dumps([c.to_dict() for c in custodians], indent=2) + "\n", encoding="utf-8"
    )

    print(
        f"Planning {args.messages} messages across {len(custodians)} custodians "
        f"(seed={args.seed}, email-ratio={args.email_ratio}, attachment-rate={args.attachment_rate})",
        flush=True,
    )
    plans = plan_corpus(
        rng,
        custodians,
        total_messages=args.messages,
        email_ratio=args.email_ratio,
        attachment_rate=args.attachment_rate,
        window_days=args.window_days,
        external_id_prefix=args.external_id_prefix,
    )

    completed = load_completed_ids(jsonl_path, args.dry_run) if args.resume else set()
    if completed:
        print(f"Resume: skipping {len(completed)} already-submitted messages", flush=True)
    pending = [p for p in plans if p.externalMessageId not in completed]

    url = args.api_url.rstrip("/") + INGESTION_PATH
    limiter = RateLimiter(args.rate)
    reporter = ProgressReporter(total=len(pending))
    write_lock = threading.Lock()
    thread_local = threading.local()

    def session_for_thread() -> requests.Session:
        """Return (and lazily create) this thread's HTTP session."""
        session = getattr(thread_local, "session", None)
        if session is None:
            session = requests.Session()
            session.headers.update({"Content-Type": "application/json"})
            thread_local.session = session
        return session

    def handle(plan: MessagePlan) -> None:
        """Materialise, submit and record a single planned message."""
        payload, manifest, attachment_bytes = build_payload(plan, attachments_dir, args.max_attachment_bytes)
        if args.dry_run:
            result = SubmissionResult(
                externalMessageId=plan.externalMessageId,
                httpStatus=None,
                requestId=None,
                deduplicationKey=None,
                error=None,
                submittedAt=iso_utc(dt.datetime.now(dt.timezone.utc)),
            )
        else:
            limiter.acquire()
            result = submit_message(session_for_thread(), url, payload, args.timeout)

        record = dict(payload)
        if manifest:
            record["attachments"] = (
                [dict(item, contentBase64=sent["contentBase64"]) for item, sent in zip(manifest, payload["attachments"])]
                if args.include_base64_in_jsonl
                else manifest
            )
        record["_submission"] = result.to_dict()
        line = json.dumps(record, ensure_ascii=False)
        with write_lock:
            with jsonl_path.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")
        reporter.record(result.ok, len(manifest), attachment_bytes, result.duplicate)

    if pending:
        if args.dry_run or args.workers == 1:
            for plan in pending:
                handle(plan)
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
                list(pool.map(handle, pending))
    else:
        print("Nothing to do: every planned message is already recorded as submitted.", flush=True)

    summary = build_summary(plans, custodians, reporter, skipped=len(completed))
    (output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    if args.upload_to_s3:
        uploaded, error = upload_attachments_to_s3(attachments_dir, args.s3_prefix)
        summary["s3Uploaded"] = uploaded
        summary["s3Error"] = error
        (output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        if error:
            print(f"S3 archival incomplete ({uploaded} uploaded): {error}", flush=True)
        else:
            print(f"S3 archival complete: {uploaded} attachment files uploaded", flush=True)

    print_final_summary(summary, output_dir, args.dry_run)
    return 1 if summary["failed"] else 0


def build_summary(
    plans: Sequence[MessagePlan],
    custodians: Sequence[Custodian],
    reporter: ProgressReporter,
    skipped: int,
) -> dict[str, Any]:
    """Build the ``summary.json`` payload from the plan and the run counters."""
    emails = sum(1 for p in plans if p.communicationType == "EMAIL")
    with_attachments = sum(1 for p in plans if p.attachments)
    return {
        "total": len(plans),
        "email": emails,
        "chat": len(plans) - emails,
        "withAttachments": with_attachments,
        "attachmentCount": reporter.attachments,
        "custodians": len(custodians),
        "threads": len({p.threadId for p in plans}),
        "submitted": reporter.submitted,
        "duplicates": reporter.duplicates,
        "failed": reporter.failed,
        "durationSeconds": round(reporter.elapsed, 2),
        "skippedByResume": skipped,
        "attachmentBytes": reporter.attachment_bytes,
    }


def print_final_summary(summary: dict[str, Any], output_dir: Path, dry_run: bool) -> None:
    """Print the human-readable end-of-run report."""
    mode = "DRY RUN (no API calls)" if dry_run else "SUBMITTED to ingestion API"
    print("")
    print("=" * 62)
    print(f"Corpus generation complete - {mode}")
    print("=" * 62)
    print(f"  Messages planned      : {summary['total']}")
    print(f"  EMAIL / CHAT          : {summary['email']} / {summary['chat']}")
    print(f"  Threads               : {summary['threads']}")
    print(f"  Custodians            : {summary['custodians']}")
    print(f"  Messages w/ attachment: {summary['withAttachments']}")
    print(f"  Attachments uploaded  : {summary['attachmentCount']} "
          f"({summary['attachmentBytes'] / 1_048_576:.1f} MiB)")
    print(f"  Submitted / failed    : {summary['submitted']} / {summary['failed']}")
    print(f"  Accepted as duplicate : {summary['duplicates']}")
    print(f"  Skipped (--resume)    : {summary['skippedByResume']}")
    print(f"  Duration              : {summary['durationSeconds']}s")
    print(f"  Output directory      : {output_dir}")
    print("=" * 62)


def main(argv: Sequence[str] | None = None) -> int:
    """Entry point: parse arguments and run the generator."""
    args = parse_args(argv)
    try:
        return run(args)
    except KeyboardInterrupt:
        print("\nInterrupted; partial results remain in the output directory.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
