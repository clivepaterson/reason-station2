use std::{collections::HashSet, env, sync::Arc};

use aws_sdk_s3::types::Tag;
use aws_sdk_sesv2::{
    primitives::Blob,
    types::{Destination, EmailContent, RawMessage},
};
use base64::{engine::general_purpose::STANDARD, Engine};
use lambda_runtime::{service_fn, Error, LambdaEvent};
use serde::Deserialize;
use tracing::{info, warn};

const FORWARDED_TAG: &str = "reasonstation-forwarded";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SesEvent {
    #[serde(rename = "Records")]
    records: Vec<SesRecord>,
}

#[derive(Debug, Deserialize)]
struct SesRecord {
    ses: SesNotification,
}

#[derive(Debug, Deserialize)]
struct SesNotification {
    mail: SesMail,
    receipt: SesReceipt,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SesMail {
    message_id: String,
    source: String,
    destination: Vec<String>,
    common_headers: CommonHeaders,
}

#[derive(Debug, Deserialize)]
struct CommonHeaders {
    subject: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SesReceipt {
    spam_verdict: Verdict,
    virus_verdict: Verdict,
}

#[derive(Debug, Deserialize)]
struct Verdict {
    status: String,
}

struct App {
    s3: aws_sdk_s3::Client,
    ses: aws_sdk_sesv2::Client,
    bucket: String,
    object_prefix: String,
    forward_from: String,
    forward_to: String,
}

impl App {
    async fn handle(&self, event: LambdaEvent<SesEvent>) -> Result<(), Error> {
        for record in event.payload.records {
            self.forward(record.ses).await?;
        }
        Ok(())
    }

    async fn forward(&self, notification: SesNotification) -> Result<(), Error> {
        validate_message_id(&notification.mail.message_id)?;
        let key = format!("{}{}", self.object_prefix, notification.mail.message_id);

        let existing_tags = self
            .s3
            .get_object_tagging()
            .bucket(&self.bucket)
            .key(&key)
            .send()
            .await?
            .tag_set;

        if existing_tags
            .iter()
            .any(|tag| tag.key() == FORWARDED_TAG && tag.value() == "true")
        {
            info!(message_id = %notification.mail.message_id, "email already forwarded");
            return Ok(());
        }

        let object = self
            .s3
            .get_object()
            .bucket(&self.bucket)
            .key(&key)
            .send()
            .await?;
        let original = object.body.collect().await?.into_bytes();
        let subject = notification
            .mail
            .common_headers
            .subject
            .as_deref()
            .unwrap_or("(no subject)");
        let forwarded = build_forwarded_message(
            &original,
            &self.forward_from,
            &self.forward_to,
            &notification.mail.source,
            subject,
            &notification.mail.destination,
            &notification.receipt,
        )?;

        let destination = Destination::builder()
            .to_addresses(&self.forward_to)
            .build();
        let raw = RawMessage::builder()
            .data(Blob::new(forwarded))
            .build()?;
        self.ses
            .send_email()
            .from_email_address(&self.forward_from)
            .destination(destination)
            .content(EmailContent::builder().raw(raw).build())
            .send()
            .await?;

        let mut tags = existing_tags;
        tags.retain(|tag| tag.key() != FORWARDED_TAG);
        tags.push(
            Tag::builder()
                .key(FORWARDED_TAG)
                .value("true")
                .build()?,
        );
        self.s3
            .put_object_tagging()
            .bucket(&self.bucket)
            .key(&key)
            .tagging(
                aws_sdk_s3::types::Tagging::builder()
                    .set_tag_set(Some(tags))
                    .build()?,
            )
            .send()
            .await?;

        info!(
            message_id = %notification.mail.message_id,
            destination = %self.forward_to,
            "email forwarded"
        );
        Ok(())
    }
}

fn validate_message_id(message_id: &str) -> Result<(), Error> {
    if message_id.is_empty()
        || message_id.contains('/')
        || message_id.contains('\\')
        || message_id.contains("..")
    {
        return Err("invalid SES message ID".into());
    }
    Ok(())
}

fn build_forwarded_message(
    original: &[u8],
    from: &str,
    to: &str,
    reply_to: &str,
    subject: &str,
    original_destinations: &[String],
    receipt: &SesReceipt,
) -> Result<Vec<u8>, Error> {
    let (headers, body, line_ending) = split_message(original)?;
    let retained_headers = retain_content_headers(headers, line_ending);
    let safe_reply_to = sanitize_header(reply_to);
    let safe_subject = sanitize_header(subject);
    let original_to = original_destinations
        .iter()
        .map(|value| sanitize_header(value))
        .collect::<Vec<_>>()
        .join(", ");

    if receipt.virus_verdict.status != "PASS" {
        warn!(verdict = %receipt.virus_verdict.status, "forwarding message with non-PASS virus verdict");
    }
    if receipt.spam_verdict.status != "PASS" {
        warn!(verdict = %receipt.spam_verdict.status, "forwarding message with non-PASS spam verdict");
    }

    let encoded_subject = encode_subject(&format!("Fwd: {safe_subject}"));
    let mut output = Vec::with_capacity(original.len() + 512);
    let newline = std::str::from_utf8(line_ending)?;
    output.extend_from_slice(
        format!(
            "From: ReasonStation Email Forwarder <{from}>{newline}\
             To: {to}{newline}\
             Reply-To: {safe_reply_to}{newline}\
             Subject: {encoded_subject}{newline}\
             X-Original-To: {original_to}{newline}"
        )
        .as_bytes(),
    );
    for header in retained_headers {
        output.extend_from_slice(&header);
        output.extend_from_slice(line_ending);
    }
    output.extend_from_slice(line_ending);
    output.extend_from_slice(body);
    Ok(output)
}

fn split_message(original: &[u8]) -> Result<(&[u8], &[u8], &[u8]), Error> {
    if let Some(index) = original.windows(4).position(|window| window == b"\r\n\r\n") {
        return Ok((&original[..index], &original[index + 4..], b"\r\n"));
    }
    if let Some(index) = original.windows(2).position(|window| window == b"\n\n") {
        return Ok((&original[..index], &original[index + 2..], b"\n"));
    }
    Err("incoming email has no header/body separator".into())
}

fn retain_content_headers(headers: &[u8], line_ending: &[u8]) -> Vec<Vec<u8>> {
    let allowed: HashSet<&str> = [
        "mime-version",
        "content-type",
        "content-transfer-encoding",
        "content-language",
    ]
    .into_iter()
    .collect();
    let mut retained: Vec<Vec<u8>> = Vec::new();

    for line in headers.split(|byte| *byte == b'\n') {
        let line = line.strip_suffix(b"\r").unwrap_or(line);
        if line.starts_with(b" ") || line.starts_with(b"\t") {
            if let Some(last) = retained.last_mut() {
                last.extend_from_slice(line_ending);
                last.extend_from_slice(line);
            }
            continue;
        }

        let Some(colon) = line.iter().position(|byte| *byte == b':') else {
            continue;
        };
        let name = String::from_utf8_lossy(&line[..colon]).to_ascii_lowercase();
        if allowed.contains(name.as_str()) {
            retained.push(line.to_vec());
        }
    }
    retained
}

fn sanitize_header(value: &str) -> String {
    value
        .chars()
        .map(|character| match character {
            '\r' | '\n' | '\0' => ' ',
            _ => character,
        })
        .collect::<String>()
        .trim()
        .to_owned()
}

fn encode_subject(subject: &str) -> String {
    if subject.is_ascii() {
        subject.to_owned()
    } else {
        format!("=?UTF-8?B?{}?=", STANDARD.encode(subject.as_bytes()))
    }
}

fn required_env(name: &str) -> Result<String, Error> {
    env::var(name).map_err(|_| format!("missing required environment variable {name}").into())
}

#[tokio::main]
async fn main() -> Result<(), Error> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .json()
        .with_target(false)
        .without_time()
        .init();

    let config = aws_config::defaults(aws_config::BehaviorVersion::latest())
        .load()
        .await;
    let app = Arc::new(App {
        s3: aws_sdk_s3::Client::new(&config),
        ses: aws_sdk_sesv2::Client::new(&config),
        bucket: required_env("EMAIL_BUCKET")?,
        object_prefix: required_env("EMAIL_OBJECT_PREFIX")?,
        forward_from: required_env("FORWARD_FROM")?,
        forward_to: required_env("FORWARD_TO")?,
    });

    lambda_runtime::run(service_fn(move |event| {
        let app = Arc::clone(&app);
        async move { app.handle(event).await }
    }))
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pass_receipt() -> SesReceipt {
        SesReceipt {
            spam_verdict: Verdict {
                status: "PASS".to_owned(),
            },
            virus_verdict: Verdict {
                status: "PASS".to_owned(),
            },
        }
    }

    #[test]
    fn rewrites_envelope_headers_and_preserves_mime_body() {
        let original = b"From: attacker@example.com\r\nTo: old@example.com\r\nSubject: Original\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\nX-Untrusted: remove\r\n\r\nHello";
        let result = build_forwarded_message(
            original,
            "forwarder@reasonstation2.net",
            "clive.paterson@gmail.com",
            "sender@example.com",
            "Original",
            &["hello@reasonstation2.net".to_owned()],
            &pass_receipt(),
        )
        .unwrap();
        let text = String::from_utf8(result).unwrap();

        assert!(text.contains("From: ReasonStation Email Forwarder <forwarder@reasonstation2.net>"));
        assert!(text.contains("Reply-To: sender@example.com"));
        assert!(text.contains("Subject: Fwd: Original"));
        assert!(text.contains("MIME-Version: 1.0"));
        assert!(text.ends_with("\r\n\r\nHello"));
        assert!(!text.contains("X-Untrusted"));
        assert!(!text.contains("To: old@example.com"));
    }

    #[test]
    fn rejects_header_injection() {
        assert_eq!(
            sanitize_header("sender@example.com\r\nBcc: victim@example.com"),
            "sender@example.com  Bcc: victim@example.com"
        );
    }
}
