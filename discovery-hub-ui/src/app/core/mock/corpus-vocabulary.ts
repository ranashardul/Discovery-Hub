/**
 * Vocabulary for the synthetic corpus. The wording is deliberately
 * compliance-flavoured (trading desks, disclosures, escalations) so that the
 * search screen surfaces the kind of hits an investigator would actually chase.
 */

export const FIRST_NAMES = [
  'Amara', 'Priya', 'Daniel', 'Sofia', 'Marcus', 'Wei', 'Elena', 'Tobias',
  'Nadia', 'Rahul', 'Chloe', 'Hugo', 'Ines', 'Kwame', 'Laura', 'Mateo',
  'Nora', 'Omar', 'Petra', 'Quentin', 'Rosa', 'Samir', 'Tara', 'Viktor',
  'Yara', 'Zane',
] as const;

export const LAST_NAMES = [
  'Okafor', 'Nair', 'Whitfield', 'Marino', 'Delgado', 'Zhang', 'Petrova',
  'Lindqvist', 'Haddad', 'Menon', 'Beaumont', 'Serrano', 'Falk', 'Mensah',
  'Kovacs', 'Ibarra', 'Lindberg', 'Rahman', 'Novak', 'Dubois', 'Alvarez',
  'Chowdhury', 'Sundaram', 'Ilyin', 'Baptiste', 'Moreau',
] as const;

export const DEPARTMENTS = [
  'Fixed Income Trading',
  'Equity Sales',
  'Compliance',
  'Wealth Advisory',
  'Investment Banking',
  'Treasury',
  'Risk',
  'Operations',
] as const;

export const TITLES = [
  'Managing Director',
  'Executive Director',
  'Vice President',
  'Associate',
  'Analyst',
  'Desk Head',
  'Compliance Officer',
  'Portfolio Manager',
] as const;

export const EMAIL_SUBJECTS = [
  'Q3 budget review for the {desk} desk',
  'RE: {ticker} block trade allocation',
  'Client suitability documentation — {client}',
  'Escalation: unapproved communication channel',
  'Pre-trade approval request ({ticker})',
  'Marketing material review — {client} pitchbook',
  'Best execution exception report',
  'FW: {client} onboarding — outstanding KYC items',
  'Quarterly attestation reminder',
  'Restricted list update effective Monday',
  'Trade break on {ticker} settlement',
  'Off-channel messaging policy refresher',
  'Personal account dealing disclosure',
  'Gift and entertainment pre-clearance — {client}',
  'Wall crossing request: {ticker}',
  'Model portfolio rebalance rationale',
  'Regulatory inquiry — document preservation notice',
  'Month-end reconciliation variance',
  'Research distribution list change',
  'Expense report follow-up',
] as const;

export const CHAT_SUBJECTS = [
  'Trading floor — {desk}',
  'Deal team — {client}',
  'Ops escalation channel',
  'Compliance queries',
  '{ticker} coverage huddle',
  'Morning call notes',
  'Client coverage — {client}',
  'Desk banter',
] as const;

export const EMAIL_BODIES = [
  'Following up on the budget review for the {desk} desk. Headcount is flat but the market data spend is running {pct}% over plan. Can you confirm the accrual before we lock the quarter?',
  'The block in {ticker} filled at the average price we discussed. Allocation across the two accounts follows the pro-rata rule; please confirm you are comfortable before I book it.',
  'Attaching the suitability file for {client}. The risk profile was refreshed last month and the concentration limit is documented in section 4. Flagging that one holding sits just above the threshold.',
  'We picked up a message thread that appears to have moved to a personal device. Per policy this needs to be escalated to Compliance today, and the participants should be reminded in writing.',
  'Requesting pre-trade approval for {ticker}. Position is within the desk limit and the name is not on the restricted list as of this morning. Please respond before the open.',
  'Legal has reviewed the {client} pitchbook. Two disclosures were missing on the performance page and the projection language needs to be softened. Revised deck to follow.',
  'This quarter shows {pct}% of orders routed outside the primary venue. The exception report is attached; the rationale in each case was size, not price improvement.',
  'The {client} onboarding is blocked on outstanding KYC items — beneficial ownership confirmation and the source of wealth narrative. We cannot trade the account until both clear.',
  'Reminder that the quarterly attestation is due Friday. This covers outside business activities, personal account dealing, and the use of approved communication channels only.',
  'The restricted list has been updated and takes effect Monday. Two names were added following a mandate we cannot discuss on this thread. Please clear any open interest today.',
  'We have a trade break on {ticker}. The counterparty confirms a different settlement date; Operations is reconciling and I will escalate if it is not resolved by close of business.',
  'A reminder that business communications must stay on approved channels and are retained. Messages sent through consumer applications create a records gap we cannot defend to the regulator.',
  'Disclosing a personal account transaction executed yesterday. It was pre-cleared through the portal and involved no name I cover professionally.',
  'Pre-clearance request for a client dinner with {client}. Estimated value is under the reporting threshold but I would rather have it on the record.',
  'Requesting a wall crossing for {ticker}. The public side team needs to be brought over the wall for one call; the control room should log the crossing.',
  'The rebalance moves the model {pct}% toward short duration. Rationale, committee minutes, and the client communication template are attached for the file.',
  'We have received a regulatory inquiry covering the period in question. Do not delete anything — a preservation notice is in force across the relevant mailboxes and chat channels.',
  'Month-end reconciliation shows a variance of {pct}% against the sub-ledger. Working with Treasury to identify whether this is a timing difference or a booking error.',
  'Updating the research distribution list. Please remove anyone who has left the coverage group; unsolicited distribution creates a compliance problem.',
  'Your expense submission is missing the itemised receipt and the client attendee list. Both are required before Finance can release payment.',
] as const;

export const CHAT_BODIES = [
  'can you look at the {ticker} print before the close',
  'client asked about fees again — sending them the disclosure doc',
  'do not put that in writing, take it to the desk phone',
  'compliance flagged the thread, expect questions',
  'need the allocation confirmed in the next ten minutes',
  '{client} wants a call at 4, are you free',
  'the {desk} numbers look off by about {pct}%',
  'preservation notice landed, nothing gets deleted from today',
  'moving this to the approved channel, one moment',
  'who has the latest restricted list',
  'trade broke, ops is on it',
  'attestation reminder is out, please do it today',
  'can you check whether {ticker} is on the wall crossing log',
  'sending the revised pitch for {client} now',
  'that allocation needs a pro rata rationale in the file',
  'my read is we escalate rather than sit on it',
] as const;

export const CLIENTS = [
  'Northwind Pension', 'Arclight Capital', 'Bramble Family Office',
  'Coastal Mutual', 'Deltaic Partners', 'Everline Insurance',
  'Foxglove Endowment', 'Granite Sovereign', 'Halyard Asset Management',
  'Ironvale Trust',
] as const;

export const TICKERS = [
  'NWD', 'ARCL', 'BRMB', 'CSTL', 'DLTA', 'EVRL', 'FXGL', 'GRNT', 'HLYD', 'IRNV',
] as const;

export const ATTACHMENT_TEMPLATES = [
  { suffix: 'suitability-review.pdf', contentType: 'application/pdf', min: 180_000, max: 2_400_000 },
  { suffix: 'allocation-schedule.xlsx', contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', min: 24_000, max: 480_000 },
  { suffix: 'pitchbook.pptx', contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', min: 1_200_000, max: 12_000_000 },
  { suffix: 'exception-report.csv', contentType: 'text/csv', min: 2_000, max: 90_000 },
  { suffix: 'kyc-packet.zip', contentType: 'application/zip', min: 400_000, max: 6_000_000 },
  { suffix: 'call-notes.docx', contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', min: 18_000, max: 260_000 },
  { suffix: 'trade-confirm.eml', contentType: 'message/rfc822', min: 6_000, max: 40_000 },
  { suffix: 'screenshot.png', contentType: 'image/png', min: 60_000, max: 1_800_000 },
] as const;

export const ACTORS = [
  'ines.beaumont@stonewall-bank.example',
  'marcus.delgado@stonewall-bank.example',
  'nadia.haddad@stonewall-bank.example',
  'system@discoveryhub',
] as const;

export const CASE_SEEDS = [
  {
    name: 'Off-channel messaging review — Fixed Income',
    description:
      'Review of business communications conducted over unapproved consumer messaging applications by the Fixed Income Trading desk during the second and third quarters.',
    matterType: 'INVESTIGATION' as const,
    status: 'OPEN' as const,
  },
  {
    name: 'Northwind Pension suitability dispute',
    description:
      'Litigation hold covering advisory communications with Northwind Pension relating to concentration limits and the disputed rebalance.',
    matterType: 'LITIGATION' as const,
    status: 'OPEN' as const,
  },
  {
    name: 'Regulatory inquiry — best execution 2026',
    description:
      'Response to a regulator request for communications evidencing venue selection and best execution exception handling.',
    matterType: 'REGULATORY_INQUIRY' as const,
    status: 'OPEN' as const,
  },
  {
    name: 'Personal account dealing sweep',
    description:
      'Routine surveillance sweep of pre-clearance and disclosure communications ahead of the quarterly attestation cycle.',
    matterType: 'INVESTIGATION' as const,
    status: 'ARCHIVED' as const,
  },
  {
    name: 'Arclight wall crossing audit',
    description:
      'Closed matter reviewing control room logging of wall crossings for the Arclight Capital mandate.',
    matterType: 'REGULATORY_INQUIRY' as const,
    status: 'CLOSED' as const,
  },
] as const;
